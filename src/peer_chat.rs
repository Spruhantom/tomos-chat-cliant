//! Encrypted peer chat worker threads for the GTK client.
//!
//! Application payloads use the same bounded, versioned frame format as the
//! Android client. Every frame is passed through the authenticated Noise
//! channel; this module never writes application plaintext to TCP.

use std::collections::{HashSet, VecDeque};
use std::sync::mpsc::{self, Receiver, Sender, TryRecvError};
use std::thread;
use tomchat_noise::app_frame::{AppFrame, AppFrameType, MAX_APP_PAYLOAD_BYTES};
use tomchat_noise::peer_transport::{EncryptedPeerChannel, PeerReceiver, PeerSender};

const RECENT_MESSAGE_WINDOW: usize = 2048;

pub struct PeerChat {
    outgoing: Sender<Option<Vec<u8>>>,
    incoming: Receiver<Result<String, String>>,
}

impl PeerChat {
    pub fn start(channel: EncryptedPeerChannel) -> Result<Self, String> {
        let (sender, receiver) = channel.split().map_err(|error| error.to_string())?;
        let (outgoing, outgoing_rx) = mpsc::channel::<Option<Vec<u8>>>();
        let (incoming_tx, incoming) = mpsc::channel::<Result<String, String>>();

        spawn_sender(sender, outgoing_rx, incoming_tx.clone());
        spawn_receiver(receiver, incoming_tx, outgoing.clone());

        Ok(Self { outgoing, incoming })
    }

    pub fn send(&self, message: String) -> Result<(), String> {
        if message.trim().is_empty() {
            return Err("Message cannot be empty.".into());
        }
        if message.as_bytes().len() > MAX_APP_PAYLOAD_BYTES {
            return Err("Message exceeds the 16 KiB application payload limit.".into());
        }
        let frame = AppFrame::new(AppFrameType::Text, message.into_bytes())
            .map_err(|error| error.to_string())?;
        let encoded = frame.encode().map_err(|error| error.to_string())?;
        self.outgoing
            .send(Some(encoded))
            .map_err(|_| "Peer connection is closed.".to_string())
    }

    pub fn try_receive(&self) -> Option<Result<String, String>> {
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

fn spawn_sender(
    mut sender: PeerSender,
    outgoing: Receiver<Option<Vec<u8>>>,
    incoming: Sender<Result<String, String>>,
) {
    thread::spawn(move || {
        while let Ok(Some(frame)) = outgoing.recv() {
            if let Err(error) = sender.send(&frame) {
                let _ = incoming.send(Err(format!("Encrypted send failed: {error}")));
                return;
            }
        }
        sender.shutdown();
    });
}

fn spawn_receiver(
    mut receiver: PeerReceiver,
    incoming: Sender<Result<String, String>>,
    outgoing: Sender<Option<Vec<u8>>>,
) {
    thread::spawn(move || {
        let mut seen = HashSet::<[u8; 16]>::new();
        let mut order = VecDeque::<[u8; 16]>::new();

        loop {
            let plaintext = match receiver.receive() {
                Ok(bytes) => bytes,
                Err(error) => {
                    let _ = incoming.send(Err(format!("Encrypted receive failed: {error}")));
                    break;
                }
            };
            let frame = match AppFrame::decode(&plaintext) {
                Ok(frame) => frame,
                Err(error) => {
                    let _ = incoming.send(Err(format!("Peer application frame was rejected: {error}")));
                    break;
                }
            };

            match frame.frame_type {
                AppFrameType::DeliveryAck => {
                    if !frame.payload.is_empty() {
                        let _ = incoming.send(Err("Malformed delivery acknowledgement.".into()));
                        break;
                    }
                }
                AppFrameType::Text => {
                    let message = match String::from_utf8(frame.payload) {
                        Ok(message) if !message.trim().is_empty() => message,
                        _ => {
                            let _ = incoming.send(Err("Peer sent invalid or empty UTF-8 text.".into()));
                            break;
                        }
                    };

                    let acknowledgement = match AppFrame::with_id(
                        AppFrameType::DeliveryAck,
                        frame.message_id,
                        Vec::new(),
                    )
                    .and_then(|ack| ack.encode())
                    {
                        Ok(bytes) => bytes,
                        Err(error) => {
                            let _ = incoming.send(Err(error.to_string()));
                            break;
                        }
                    };
                    if outgoing.send(Some(acknowledgement)).is_err() {
                        let _ = incoming.send(Err("Peer connection is closing.".into()));
                        break;
                    }

                    if seen.insert(frame.message_id) {
                        order.push_back(frame.message_id);
                        if order.len() > RECENT_MESSAGE_WINDOW {
                            if let Some(expired) = order.pop_front() {
                                seen.remove(&expired);
                            }
                        }
                        if incoming.send(Ok(message)).is_err() {
                            break;
                        }
                    }
                    // Duplicate authenticated message IDs are acknowledged again,
                    // but never surfaced to the UI a second time.
                }
                _ => {
                    let _ = incoming.send(Err(
                        "This Linux client build does not yet support that application frame type."
                            .into(),
                    ));
                    break;
                }
            }
        }
    });
}
