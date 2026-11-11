//! Encrypted peer chat worker threads for the GTK client.

use std::sync::mpsc::{self, Receiver, Sender, TryRecvError};
use std::thread;
use tomchat_noise::peer_transport::{EncryptedPeerChannel, PeerReceiver, PeerSender};

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
        spawn_receiver(receiver, incoming_tx);

        Ok(Self { outgoing, incoming })
    }

    pub fn send(&self, message: String) -> Result<(), String> {
        if message.trim().is_empty() {
            return Err("Message cannot be empty.".into());
        }
        self.outgoing.send(Some(message.into_bytes()))
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
        while let Ok(Some(message)) = outgoing.recv() {
            if let Err(error) = sender.send(&message) {
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
) {
    thread::spawn(move || loop {
        match receiver.receive() {
            Ok(bytes) => match String::from_utf8(bytes) {
                Ok(message) => {
                    if incoming.send(Ok(message)).is_err() { break; }
                }
                Err(_) => {
                    let _ = incoming.send(Err("Peer sent a non-text message.".into()));
                    break;
                }
            },
            Err(error) => {
                let _ = incoming.send(Err(format!("Encrypted receive failed: {error}")));
                break;
            }
        }
    });
}
