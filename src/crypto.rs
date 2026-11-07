//! First MLS integration milestone for TomChat.
//!
//! This module proves that OpenMLS can create an MLS group and exchange
//! ciphertext between two independent client providers. It is deliberately
//! isolated from GTK and the REST transport. Production use is blocked on
//! durable encrypted storage, device verification, server directory APIs,
//! replay/fork handling, and independent security review.

use openmls::prelude::*;

pub const TOMCHAT_MLS_CIPHERSUITE: Ciphersuite =
    Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

#[cfg(test)]
mod tests {
    use super::*;
    use openmls_basic_credential::SignatureKeyPair;
    use openmls_rust_crypto::OpenMlsRustCrypto;
    use openmls_traits::OpenMlsProvider;
    use tls_codec::{Deserialize, Serialize};

    fn create_credential(
        identity: &[u8],
        provider: &OpenMlsRustCrypto,
    ) -> (CredentialWithKey, SignatureKeyPair) {
        let credential = BasicCredential::new(identity.to_vec());
        let signer = SignatureKeyPair::new(TOMCHAT_MLS_CIPHERSUITE.signature_algorithm())
            .expect("generate MLS signing key");
        signer
            .store(provider.storage())
            .expect("store MLS signing key in provider storage");

        (
            CredentialWithKey {
                credential: credential.into(),
                signature_key: signer.public().into(),
            },
            signer,
        )
    }

    #[test]
    fn two_devices_exchange_ciphertext_and_only_group_member_decrypts() {
        let alice_provider = OpenMlsRustCrypto::default();
        let bob_provider = OpenMlsRustCrypto::default();
        let outsider_provider = OpenMlsRustCrypto::default();

        let (alice_credential, alice_signer) =
            create_credential(b"tomchat-test-alice", &alice_provider);
        let (bob_credential, bob_signer) = create_credential(b"tomchat-test-bob", &bob_provider);
        let (outsider_credential, outsider_signer) =
            create_credential(b"tomchat-test-outsider", &outsider_provider);

        let bob_key_package = KeyPackage::builder()
            .build(
                TOMCHAT_MLS_CIPHERSUITE,
                &bob_provider,
                &bob_signer,
                bob_credential,
            )
            .expect("build Bob's one-time KeyPackage");

        let group_config = MlsGroupCreateConfig::builder()
            .ciphersuite(TOMCHAT_MLS_CIPHERSUITE)
            .wire_format_policy(PURE_CIPHERTEXT_WIRE_FORMAT_POLICY)
            .use_ratchet_tree_extension(true)
            .build();

        let mut alice_group = MlsGroup::new(
            &alice_provider,
            &alice_signer,
            &group_config,
            alice_credential,
        )
        .expect("create Alice's MLS group");

        let (_commit, welcome, _group_info) = alice_group
            .add_members(
                &alice_provider,
                &alice_signer,
                std::slice::from_ref(bob_key_package.key_package()),
            )
            .expect("add Bob to the MLS group");
        alice_group
            .merge_pending_commit(&alice_provider)
            .expect("merge Alice's membership commit");

        let welcome_bytes = welcome
            .tls_serialize_detached()
            .expect("serialize MLS Welcome");
        let welcome_in =
            MlsMessageIn::tls_deserialize_exact(&welcome_bytes).expect("parse MLS Welcome");
        let welcome = match welcome_in.extract() {
            MlsMessageBodyIn::Welcome(welcome) => welcome,
            _ => panic!("expected MLS Welcome message"),
        };
        let mut bob_group = StagedWelcome::new_from_welcome(
            &bob_provider,
            &MlsGroupJoinConfig::default(),
            welcome,
            Some(alice_group.export_ratchet_tree().into()),
        )
        .expect("stage Bob's MLS group join")
        .into_group(&bob_provider)
        .expect("complete Bob's MLS group join");

        assert_eq!(alice_group.members().count(), 2);
        assert_eq!(bob_group.members().count(), 2);

        let plaintext = b"TomChat MLS encrypted test message";
        let encrypted = alice_group
            .create_message(&alice_provider, &alice_signer, plaintext)
            .expect("encrypt MLS application message")
            .tls_serialize_detached()
            .expect("serialize MLS ciphertext");

        assert!(
            !encrypted
                .windows(plaintext.len())
                .any(|window| window == plaintext),
            "plaintext must not appear in the serialized MLS message"
        );

        let incoming = MlsMessageIn::tls_deserialize_exact(&encrypted)
            .expect("parse encrypted MLS application message");
        let protocol_message = incoming
            .try_into_protocol_message()
            .expect("convert to MLS protocol message");
        let processed = bob_group
            .process_message(&bob_provider, protocol_message)
            .expect("Bob decrypts the group message");

        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(message) => {
                assert_eq!(message.into_bytes(), plaintext);
            }
            _ => panic!("expected decrypted MLS application message"),
        }

        let mut outsider_group = MlsGroup::new(
            &outsider_provider,
            &outsider_signer,
            &group_config,
            outsider_credential,
        )
        .expect("create unrelated outsider group");
        let incoming =
            MlsMessageIn::tls_deserialize_exact(&encrypted).expect("parse outsider test message");
        let protocol_message = incoming
            .try_into_protocol_message()
            .expect("convert outsider message");
        assert!(
            outsider_group
                .process_message(&outsider_provider, protocol_message)
                .is_err(),
            "a device outside the MLS group must not decrypt the message"
        );
    }
}
