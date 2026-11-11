mod contact_card;
mod contact_store;
mod peer;
mod peer_chat;

use adw::prelude::*;
use gtk::glib;
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::mpsc;
use std::time::Duration;
use tomchat_noise::identity::NoiseIdentity;
use zeroize::Zeroize;

fn main() {
    let app = adw::Application::builder()
        .application_id("uk.co.tomserver.TomChat")
        .build();
    app.connect_activate(build_contacts);
    app.run();
}

fn build_contacts(app: &adw::Application) {
    let window = adw::ApplicationWindow::builder()
        .application(app)
        .title("TomChat")
        .default_width(720)
        .default_height(860)
        .build();

    let header = adw::HeaderBar::new();
    let title = gtk::Label::new(Some("TomChat · Peer chat"));
    title.add_css_class("title-1");

    let status = gtk::Label::new(Some("Loading local identity and trusted contacts…"));
    status.set_wrap(true);
    status.set_xalign(0.0);

    let signing_identity = Rc::new(RefCell::new(
        contact_card::SigningIdentity::load_from_secret_service().ok(),
    ));
    let noise_identity = Rc::new(RefCell::new(NoiseIdentity::load_from_secret_service().ok()));
    let store = Rc::new(contact_store::TrustedContactStore);
    let (initial_contacts, store_status) = match store.load() {
        Ok(loaded) => (
            loaded,
            "Trusted contacts loaded from your desktop keyring.".to_string(),
        ),
        Err(error) => (
            Vec::new(),
            format!("Contact storage is unavailable: {error}"),
        ),
    };
    let contacts = Rc::new(RefCell::new(initial_contacts));

    let own_card = gtk::TextView::builder()
        .editable(false)
        .cursor_visible(false)
        .wrap_mode(gtk::WrapMode::WordChar)
        .height_request(90)
        .build();
    own_card.set_vexpand(false);
    refresh_own_card(
        &own_card,
        &signing_identity.borrow(),
        &noise_identity.borrow(),
    );

    let create_confirm = gtk::CheckButton::with_label(
        "This is a new device. I understand creating a new identity will not recover an existing one."
    );
    let create_identity = gtk::Button::with_label("Create new device identity");
    create_identity
        .set_sensitive(signing_identity.borrow().is_none() && noise_identity.borrow().is_none());
    let create_for_toggle = create_identity.clone();
    create_confirm.connect_toggled(move |check| create_for_toggle.set_sensitive(check.is_active()));

    let import_input = gtk::TextView::builder()
        .wrap_mode(gtk::WrapMode::WordChar)
        .height_request(90)
        .build();
    import_input.set_vexpand(false);
    let import_scroll = gtk::ScrolledWindow::builder()
        .child(&import_input)
        .min_content_height(90)
        .build();
    let fingerprint_confirm = gtk::CheckButton::with_label(
        "I have independently checked this contact fingerprint with the person.",
    );
    let import_contact = gtk::Button::with_label("Verify and trust contact");
    import_contact.set_sensitive(false);
    let import_for_toggle = import_contact.clone();
    fingerprint_confirm
        .connect_toggled(move |check| import_for_toggle.set_sensitive(check.is_active()));

    let contact_picker = gtk::ComboBoxText::new();
    populate_contact_picker(&contact_picker, &contacts.borrow());
    let remove_contact = gtk::Button::with_label("Remove selected contact");

    let peer_address = gtk::Entry::builder()
        .placeholder_text("Peer IP address:port (for example 192.168.1.20:46321)")
        .hexpand(true)
        .build();
    let connect = gtk::Button::with_label("Connect directly");
    connect.add_css_class("suggested-action");
    let listen = gtk::Button::with_label("Listen on port 46321");

    let chat_view = gtk::TextView::builder()
        .editable(false)
        .cursor_visible(false)
        .wrap_mode(gtk::WrapMode::WordChar)
        .vexpand(true)
        .build();
    let chat_scroll = gtk::ScrolledWindow::builder()
        .child(&chat_view)
        .min_content_height(180)
        .vexpand(true)
        .build();
    let message_input = gtk::Entry::builder()
        .placeholder_text("Write an encrypted message…")
        .hexpand(true)
        .build();
    let send = gtk::Button::with_label("Send");
    send.set_sensitive(false);
    let message_row = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    message_row.append(&message_input);
    message_row.append(&send);

    let content = gtk::Box::new(gtk::Orientation::Vertical, 12);
    content.set_margin_top(20);
    content.set_margin_bottom(20);
    content.set_margin_start(20);
    content.set_margin_end(20);
    content.append(&title);
    content.append(&status);

    let identity_heading = heading("Your signed contact card");
    content.append(&identity_heading);
    let own_scroll = gtk::ScrolledWindow::builder()
        .child(&own_card)
        .min_content_height(90)
        .build();
    content.append(&own_scroll);
    content.append(&create_confirm);
    content.append(&create_identity);

    content.append(&heading("Add a trusted contact"));
    let import_instructions = gtk::Label::new(Some(
        "Paste a signed V2 contact card. The signature is checked locally and its Noise key is bound to the signing identity. V1 cards can be imported for reference but cannot start encrypted sessions."
    ));
    import_instructions.set_wrap(true);
    import_instructions.set_xalign(0.0);
    content.append(&import_instructions);
    content.append(&import_scroll);
    content.append(&fingerprint_confirm);
    content.append(&import_contact);

    content.append(&heading("Direct connection"));
    content.append(&contact_picker);
    content.append(&remove_contact);
    content.append(&peer_address);
    let connection_buttons = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    connection_buttons.append(&connect);
    connection_buttons.append(&listen);
    content.append(&connection_buttons);

    content.append(&heading("Encrypted conversation"));
    content.append(&chat_scroll);
    content.append(&message_row);

    let page = adw::ToolbarView::new();
    page.add_top_bar(&header);
    page.set_content(Some(&content));
    window.set_content(Some(&page));

    let create_status = status.clone();
    let signing_for_create = signing_identity.clone();
    let noise_for_create = noise_identity.clone();
    let own_card_for_create = own_card.clone();
    let confirm_for_create = create_confirm.clone();
    let create_button_for_create = create_identity.clone();
    create_identity.connect_clicked(move |_| {
        if !confirm_for_create.is_active() {
            create_status.set_text("Confirm that this is a new device before creating an identity.");
            return;
        }
        if signing_for_create.borrow().is_some() || noise_for_create.borrow().is_some() {
            create_status.set_text("A partial or existing identity was found. Refusing to replace it; recovery is required.");
            return;
        }
        create_button_for_create.set_sensitive(false);
        let signing = match contact_card::SigningIdentity::generate() {
            Ok(identity) => identity,
            Err(error) => {
                create_status.set_text(&format!("Could not create signing identity: {error}"));
                return;
            }
        };
        let noise = match NoiseIdentity::generate() {
            Ok(identity) => identity,
            Err(error) => {
                create_status.set_text(&format!("Could not create Noise identity: {error}"));
                return;
            }
        };
        match signing.save_to_secret_service() {
            Ok(()) => {}
            Err(error) => {
                create_status.set_text(&format!("Could not securely save signing identity: {error}"));
                return;
            }
        }
        match noise.save_to_secret_service() {
            Ok(()) => {
                *signing_for_create.borrow_mut() = Some(signing);
                *noise_for_create.borrow_mut() = Some(noise);
                refresh_own_card(
                    &own_card_for_create,
                    &signing_for_create.borrow(),
                    &noise_for_create.borrow(),
                );
                create_status.set_text("New device identity created and saved in your desktop keyring.");
                confirm_for_create.set_active(false);
            }
            Err(error) => {
                create_status.set_text(&format!("The signing key was saved, but the Noise key could not be saved: {error}. Identity setup is incomplete; do not create another identity."));
            }
        }
    });

    let import_store = store.clone();
    let import_contacts = contacts.clone();
    let picker_for_import = contact_picker.clone();
    let input_for_import = import_input.clone();
    let status_for_import = status.clone();
    let confirm_for_import = fingerprint_confirm.clone();
    import_contact.connect_clicked(move |_| {
        if !confirm_for_import.is_active() {
            status_for_import.set_text("Check the contact fingerprint with the person before trusting this identity.");
            return;
        }
        let buffer = input_for_import.buffer();
        let start = buffer.start_iter();
        let end = buffer.end_iter();
        let encoded = buffer.text(&start, &end, false).to_string();
        if encoded.trim().is_empty() {
            status_for_import.set_text("Paste a signed contact card first.");
            return;
        }
        match import_store.import(encoded.trim()) {
            Ok(updated) => {
                *import_contacts.borrow_mut() = updated;
                populate_contact_picker(&picker_for_import, &import_contacts.borrow());
                buffer.set_text("");
                confirm_for_import.set_active(false);
                status_for_import.set_text("Contact signature verified and contact saved. Encrypted connections require a V2 card and a matching Noise handshake.");
            }
            Err(error) => status_for_import.set_text(&format!("Contact was not imported: {error}")),
        }
    });

    let remove_store = store.clone();
    let remove_contacts = contacts.clone();
    let remove_picker = contact_picker.clone();
    let remove_status = status.clone();
    remove_contact.connect_clicked(move |_| {
        let Some(contact) = selected_contact(&remove_picker, &remove_contacts.borrow()) else {
            remove_status.set_text("Select a trusted V2 contact to remove.");
            return;
        };
        match remove_store.remove(&contact.fingerprint) {
            Ok(updated) => {
                *remove_contacts.borrow_mut() = updated;
                populate_contact_picker(&remove_picker, &remove_contacts.borrow());
                remove_status.set_text("Trusted contact removed from this device.");
            }
            Err(error) => remove_status.set_text(&format!("Could not remove contact: {error}")),
        }
    });

    let (connection_tx, connection_rx) = mpsc::channel::<Result<peer_chat::PeerChat, String>>();
    let active_chat: Rc<RefCell<Option<peer_chat::PeerChat>>> = Rc::new(RefCell::new(None));

    let connect_status = status.clone();
    let connect_contacts = contacts.clone();
    let connect_picker = contact_picker.clone();
    let connect_address = peer_address.clone();
    let connect_noise = noise_identity.clone();
    let connect_tx = connection_tx.clone();
    let connect_button = connect.clone();
    let listen_button = listen.clone();
    connect.connect_clicked(move |_| {
        let Some(contact) = selected_contact(&connect_picker, &connect_contacts.borrow()) else {
            connect_status.set_text("Select a trusted V2 contact first.");
            return;
        };
        let local_identity = connect_noise.borrow();
        let Some(local) = local_identity.as_ref() else {
            connect_status.set_text("Create or recover this device's Noise identity first.");
            return;
        };
        let address = connect_address.text().to_string();
        if address.trim().is_empty() {
            connect_status.set_text("Enter the peer's IP address and port.");
            return;
        }
        let mut private_key = [0u8; 32];
        local.with_private_key(|key| private_key.copy_from_slice(key));
        connect_button.set_sensitive(false);
        listen_button.set_sensitive(false);
        connect_status.set_text("Connecting directly and authenticating the signed peer identity…");
        let tx = connect_tx.clone();
        std::thread::spawn(move || {
            let result = peer::connect_peer_with_key(&address, &private_key, &contact)
                .and_then(peer_chat::PeerChat::start);
            private_key.zeroize();
            let _ = tx.send(result);
        });
    });

    let listen_status = status.clone();
    let listen_contacts = contacts.clone();
    let listen_picker = contact_picker.clone();
    let listen_noise = noise_identity.clone();
    let listen_tx = connection_tx.clone();
    let connect_button_for_listen = connect.clone();
    let listen_button_for_listen = listen.clone();
    listen.connect_clicked(move |_| {
        let Some(contact) = selected_contact(&listen_picker, &listen_contacts.borrow()) else {
            listen_status.set_text("Select a trusted V2 contact first.");
            return;
        };
        let local_identity = listen_noise.borrow();
        let Some(local) = local_identity.as_ref() else {
            listen_status.set_text("Create or recover this device's Noise identity first.");
            return;
        };
        let mut private_key = [0u8; 32];
        local.with_private_key(|key| private_key.copy_from_slice(key));
        connect_button_for_listen.set_sensitive(false);
        listen_button_for_listen.set_sensitive(false);
        listen_status.set_text("Waiting for the selected contact on TCP port 46321. Allow incoming connections in your firewall.");
        let tx = listen_tx.clone();
        std::thread::spawn(move || {
            let result = peer::accept_peer_with_key("0.0.0.0:46321", &private_key, &contact)
                .and_then(peer_chat::PeerChat::start);
            private_key.zeroize();
            let _ = tx.send(result);
        });
    });

    let poll_chat = active_chat.clone();
    let poll_status = status.clone();
    let poll_connect = connect.clone();
    let poll_listen = listen.clone();
    let poll_send = send.clone();
    let poll_view = chat_view.clone();
    glib::timeout_add_local(Duration::from_millis(100), move || {
        match connection_rx.try_recv() {
            Ok(Ok(chat)) => {
                *poll_chat.borrow_mut() = Some(chat);
                poll_status.set_text("Direct peer authenticated. Messages are encrypted with the established Noise session.");
                poll_connect.set_sensitive(true);
                poll_listen.set_sensitive(true);
                poll_send.set_sensitive(true);
                append_chat_line(
                    &poll_view,
                    "System",
                    "Encrypted direct connection established.",
                );
            }
            Ok(Err(error)) => {
                poll_status.set_text(&error);
                poll_connect.set_sensitive(true);
                poll_listen.set_sensitive(true);
                poll_send.set_sensitive(false);
            }
            Err(mpsc::TryRecvError::Empty) => {}
            Err(mpsc::TryRecvError::Disconnected) => return glib::ControlFlow::Break,
        }

        let mut disconnected = false;
        if let Some(chat) = poll_chat.borrow().as_ref() {
            while let Some(message) = chat.try_receive() {
                match message {
                    Ok(text) => append_chat_line(&poll_view, "Peer", &text),
                    Err(error) => {
                        poll_status.set_text(&error);
                        poll_send.set_sensitive(false);
                        disconnected = true;
                        break;
                    }
                }
            }
        }
        if disconnected {
            *poll_chat.borrow_mut() = None;
        }
        glib::ControlFlow::Continue
    });

    let send_chat = active_chat.clone();
    let send_view = chat_view.clone();
    let send_input = message_input.clone();
    let send_status = status.clone();
    send.connect_clicked(move |_| {
        let message = send_input.text().to_string();
        let active = send_chat.borrow();
        let Some(chat) = active.as_ref() else {
            send_status.set_text("Connect to a trusted peer before sending messages.");
            return;
        };
        match chat.send(message.clone()) {
            Ok(()) => {
                append_chat_line(&send_view, "You", &message);
                send_input.set_text("");
            }
            Err(error) => send_status.set_text(&error),
        }
    });

    let send_chat_enter = active_chat.clone();
    let send_view_enter = chat_view.clone();
    let send_input_enter = message_input.clone();
    let send_status_enter = status.clone();
    message_input.connect_activate(move |entry| {
        let message = entry.text().to_string();
        let active = send_chat_enter.borrow();
        let Some(chat) = active.as_ref() else {
            send_status_enter.set_text("Connect to a trusted peer before sending messages.");
            return;
        };
        match chat.send(message.clone()) {
            Ok(()) => {
                append_chat_line(&send_view_enter, "You", &message);
                send_input_enter.set_text("");
            }
            Err(error) => send_status_enter.set_text(&error),
        }
    });

    if signing_identity.borrow().is_some() && noise_identity.borrow().is_some() {
        status.set_text(&store_status);
    } else if signing_identity.borrow().is_some() || noise_identity.borrow().is_some() {
        status.set_text("Only part of this device identity could be loaded. TomChat will not replace keys; recover the missing key before connecting.");
    } else {
        status.set_text(&format!(
            "{store_status} Create a new identity only if this is a new device."
        ));
    }

    window.present();
}

fn heading(text: &str) -> gtk::Label {
    let label = gtk::Label::new(Some(text));
    label.add_css_class("heading");
    label.set_xalign(0.0);
    label
}

fn refresh_own_card(
    view: &gtk::TextView,
    signing: &Option<contact_card::SigningIdentity>,
    noise: &Option<NoiseIdentity>,
) {
    let buffer = view.buffer();
    match (signing.as_ref(), noise.as_ref()) {
        (Some(signing), Some(noise)) => {
            match contact_card::ContactCard::create("TomChat desktop", signing, *noise.public_key())
                .and_then(|card| card.encode())
            {
                Ok(encoded) => buffer.set_text(&encoded),
                Err(error) => buffer.set_text(&format!("Could not create contact card: {error}")),
            }
        }
        _ => buffer.set_text(
            "Create or recover your device identity to generate a signed V2 contact card.",
        ),
    }
}

fn contact_label(contact: &contact_card::ContactCard) -> String {
    format!("{} · {}", contact.display_name, &contact.fingerprint[..8])
}

fn populate_contact_picker(picker: &gtk::ComboBoxText, contacts: &[contact_card::ContactCard]) {
    picker.remove_all();
    for contact in contacts.iter().filter(|card| card.is_noise_compatible()) {
        picker.append_text(&contact_label(contact));
    }
    if !contacts
        .iter()
        .filter(|card| card.is_noise_compatible())
        .collect::<Vec<_>>()
        .is_empty()
    {
        picker.set_active(Some(0));
    }
}

fn selected_contact(
    picker: &gtk::ComboBoxText,
    contacts: &[contact_card::ContactCard],
) -> Option<contact_card::ContactCard> {
    let selected = picker.active_text()?.to_string();
    contacts
        .iter()
        .find(|card| card.is_noise_compatible() && contact_label(card) == selected)
        .cloned()
}

fn append_chat_line(view: &gtk::TextView, sender: &str, message: &str) {
    let buffer = view.buffer();
    let mut end = buffer.end_iter();
    buffer.insert(&mut end, &format!("{sender}: {message}\n"));
}
