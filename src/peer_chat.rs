//! Cross-platform encrypted peer chat over the shared Android/Rust app protocol.
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender, TryRecvError};
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};
use tomchat_noise::app_protocol::{AppFrame, AppFrameType, MAX_PAYLOAD_LEN};
use tomchat_noise::group_protocol::GroupProtocolFrame;
use tomchat_noise::peer_transport::{EncryptedPeerChannel, PeerReceiver, PeerSender};

#[derive(Debug)]
pub enum PeerChatEvent {
    Text(String),
    GroupProtocol(GroupProtocolFrame),
    DeliveryConfirmed([u8; 16]),
    UnsupportedFrame(AppFrameType),
}

pub struct PeerChat {
    outgoing: Sender<Option<AppFrame>>,
    incoming: Receiver<Result<PeerChatEvent, String>>,
}

impl PeerChat {
    pub fn start(channel: EncryptedPeerChannel) -> Result<Self, String> {
        let (sender, receiver) = channel.split().map_err(|error| error.to_string())?;
        let (outgoing, outgoing_rx) = mpsc::channel::<Option<AppFrame>>();
        let (incoming_tx, incoming) = mpsc::channel::<Result<PeerChatEvent, String>>();

        spawn_sender(sender, outgoing_rx, incoming_tx.clone());
        spawn_receiver(receiver, outgoing.clone(), incoming_tx);

        Ok(Self { outgoing, incoming })
    }

    pub fn send(&self, message: String) -> Result<(), String> {
        if message.trim().is_empty() {
            return Err("Message cannot be empty.".into());
        }
        let payload = message.into_bytes();
        if payload.len() > MAX_PAYLOAD_LEN {
            return Err("Message exceeds the application frame limit.".into());
        }
        self.enqueue(AppFrame {
            frame_type: AppFrameType::Text,
            message_id: new_message_id(),
            payload,
        })
    }

    /// Carries an already MLS-protected group protocol message to one reachable
    /// member. This method does not encrypt group content or verify MLS state.
    #[allow(dead_code)] // Public adapter retained for the pending MLS group UI integration.
    pub fn send_group_protocol_frame(&self, frame: &GroupProtocolFrame) -> Result<(), String> {
        let payload = frame.encode().map_err(|error| error.to_string())?;
        self.enqueue(AppFrame {
            frame_type: AppFrameType::GroupControl,
            message_id: new_message_id(),
            payload,
        })
    }

    fn enqueue(&self, frame: AppFrame) -> Result<(), String> {
        self.outgoing
            .send(Some(frame))
            .map_err(|_| "Peer connection is closed.".to_string())
    }

    pub fn try_receive(&self) -> Option<Result<PeerChatEvent, String>> {
        match self.incoming.try_recv() {
            Ok(message) => Some(message),
            Err(TryRecvError::Empty) => None,
            Err(TryRecvError::Disconnected) => Some(Err("Peer connection closed.".into())),
        }
    }
}

impl Drop for PeerChat {
    fn drop(&mut self) {
        let _ = self.outgoing.send(None);
    }
}

fn new_message_id() -> [u8; 16] {
    // Message IDs are deduplication/acknowledgement labels, not cryptographic
    // identities or secrets. The authenticated Noise channel provides integrity.
    static NEXT_ID: AtomicU64 = AtomicU64::new(1);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    let counter = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    let mut id = [0u8; 16];
    id[..8].copy_from_slice(&nanos.to_be_bytes());
    id[8..].copy_from_slice(&counter.to_be_bytes());
    id
}

fn spawn_sender(
    mut sender: PeerSender,
    outgoing: Receiver<Option<AppFrame>>,
    incoming: Sender<Result<PeerChatEvent, String>>,
) {
    thread::spawn(move || {
        while let Ok(Some(frame)) = outgoing.recv() {
            let encoded = match frame.encode() {
                Ok(value) => value,
                Err(error) => {
                    let _ = incoming.send(Err(format!("Invalid application frame: {error}")));
                    return;
                }
            };
            if let Err(error) = sender.send(&encoded) {
                let _ = incoming.send(Err(format!("Encrypted send failed: {error}")));
                return;
            }
        }
        sender.shutdown();
    });
}

fn spawn_receiver(
    mut receiver: PeerReceiver,
    outgoing: Sender<Option<AppFrame>>,
    incoming: Sender<Result<PeerChatEvent, String>>,
) {
    thread::spawn(move || loop {
        let event = match receiver.receive() {
            Ok(bytes) => match AppFrame::decode(&bytes) {
                Ok(frame) => match frame.frame_type {
                    AppFrameType::Text => match String::from_utf8(frame.payload) {
                        Ok(message) => {
                            // ACK is sent only after the encrypted frame was
                            // authenticated and decoded successfully.
                            let ack = AppFrame {
                                frame_type: AppFrameType::DeliveryAck,
                                message_id: frame.message_id,
                                payload: Vec::new(),
                            };
                            let _ = outgoing.send(Some(ack));
                            Ok(PeerChatEvent::Text(message))
                        }
                        Err(_) => Err("Peer sent text that is not valid UTF-8.".into()),
                    },
                    AppFrameType::GroupControl => GroupProtocolFrame::decode(&frame.payload)
                        .map(PeerChatEvent::GroupProtocol)
                        .map_err(|error| format!("Invalid group protocol frame: {error}")),
                    AppFrameType::DeliveryAck => {
                        Ok(PeerChatEvent::DeliveryConfirmed(frame.message_id))
                    }
                    other => Ok(PeerChatEvent::UnsupportedFrame(other)),
                },
                Err(error) => Err(format!("Peer sent an invalid application frame: {error}")),
            },
            Err(error) => Err(format!("Encrypted receive failed: {error}")),
        };
        let fatal = event.is_err();
        if incoming.send(event).is_err() || fatal {
            break;
        }
    });
}
