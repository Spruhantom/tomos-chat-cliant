mod contact_card;
mod contact_store;
mod peer;
mod peer_chat;
mod qr;

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
        .default_width(1080)
        .default_height(760)
        .build();

    adw::StyleManager::default().set_color_scheme(adw::ColorScheme::ForceDark);
    let css = gtk::CssProvider::new();
    css.load_from_data(
        "window { background: #0a0a12; color: #e8e8f0; } \
         headerbar { background: #14141f; border-bottom: 1px solid #30243d; } \
         .title-1 { color: #ff2d96; font-weight: 800; } \
         .heading { color: #00f0ff; font-weight: 700; } \
         button { border-radius: 12px; padding: 8px 14px; } \
         button.suggested-action { background: #ff2d96; color: #ffffff; } \
         entry, textview, combobox, scrolledwindow { background: #14141f; color: #e8e8f0; border-color: #39304a; border-radius: 10px; } \
         checkbutton { color: #c9c9d8; } \
         separator { background: #30243d; } \
         .flat { color: #c9c9d8; text-align: left; } \
         .flat:hover { background: #1d1d2b; color: #00f0ff; } \
         .dim-label { color: #8a8aa0; font-size: 10px; letter-spacing: 2px; }"
    );
    if let Some(display) = gtk::gdk::Display::default() {
        gtk::style_context_add_provider_for_display(
            &display,
            &css,
            gtk::STYLE_PROVIDER_PRIORITY_APPLICATION,
        );
    }
    let header = adw::HeaderBar::new();
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

    let qr_actions = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    let show_qr = gtk::Button::with_label("Show my QR code");
    show_qr.add_css_class("suggested-action");
    let scan_qr = gtk::Button::with_label("Scan QR from image");
    let webcam_qr = gtk::Button::with_label("Scan with webcam");
    qr_actions.append(&show_qr);
    qr_actions.append(&scan_qr);
    qr_actions.append(&webcam_qr);

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

    let contacts_page = gtk::Box::new(gtk::Orientation::Vertical, 14);
    contacts_page.set_margin_top(24);
    contacts_page.set_margin_bottom(24);
    contacts_page.set_margin_start(24);
    contacts_page.set_margin_end(24);
    let title = gtk::Label::new(Some("TomChat"));
    title.add_css_class("title-1");
    title.set_xalign(0.0);
    contacts_page.append(&title);
    contacts_page.append(&status);

    contacts_page.append(&heading("Your signed contact card"));
    let own_scroll = gtk::ScrolledWindow::builder()
        .child(&own_card)
        .min_content_height(90)
        .build();
    contacts_page.append(&own_scroll);
    contacts_page.append(&qr_actions);
    contacts_page.append(&create_confirm);
    contacts_page.append(&create_identity);

    contacts_page.append(&heading("Add a trusted contact"));
    let import_instructions = gtk::Label::new(Some(
        "Scan a contact QR image or paste a signed V2 contact card. The signature is verified locally and the Noise key is bound to the signing identity. Compare the fingerprint with the person before trusting them."
    ));
    import_instructions.set_wrap(true);
    import_instructions.set_xalign(0.0);
    contacts_page.append(&import_instructions);
    contacts_page.append(&import_scroll);
    contacts_page.append(&fingerprint_confirm);
    contacts_page.append(&import_contact);

    contacts_page.append(&heading("Direct connection"));
    contacts_page.append(&contact_picker);
    contacts_page.append(&remove_contact);
    contacts_page.append(&peer_address);
    let connection_buttons = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    connection_buttons.append(&connect);
    connection_buttons.append(&listen);
    contacts_page.append(&connection_buttons);

    let chat_page = gtk::Box::new(gtk::Orientation::Vertical, 14);
    chat_page.set_margin_top(24);
    chat_page.set_margin_bottom(24);
    chat_page.set_margin_start(24);
    chat_page.set_margin_end(24);
    chat_page.append(&heading("Encrypted conversation"));
    let chat_intro = gtk::Label::new(Some("Messages travel directly between authenticated peers and are encrypted with the established Noise session."));
    chat_intro.set_wrap(true);
    chat_intro.set_xalign(0.0);
    chat_page.append(&chat_intro);
    chat_page.append(&chat_scroll);
    chat_page.append(&message_row);

    let settings_page = gtk::Box::new(gtk::Orientation::Vertical, 14);
    settings_page.set_margin_top(24);
    settings_page.set_margin_bottom(24);
    settings_page.set_margin_start(24);
    settings_page.set_margin_end(24);
    settings_page.append(&heading("Privacy and device identity"));
    let settings_copy = gtk::Label::new(Some("Your signing and Noise identities are stored in the desktop Secret Service keyring. TomChat does not silently replace an existing identity. Contact cards are signed, verified locally and require fingerprint confirmation before being trusted. Keep your device keyring backed up using your desktop's secure recovery process."));
    settings_copy.set_wrap(true);
    settings_copy.set_xalign(0.0);
    settings_page.append(&settings_copy);

    let stack = gtk::Stack::builder()
        .hexpand(true)
        .vexpand(true)
        .transition_type(gtk::StackTransitionType::Crossfade)
        .build();
    stack.add_named(&contacts_page, Some("contacts"));
    stack.add_named(&chat_page, Some("chats"));
    stack.add_named(&settings_page, Some("settings"));

    let sidebar = gtk::Box::new(gtk::Orientation::Vertical, 10);
    sidebar.set_width_request(190);
    sidebar.set_margin_top(20);
    sidebar.set_margin_bottom(20);
    sidebar.set_margin_start(14);
    sidebar.set_margin_end(14);
    let brand = gtk::Label::new(Some("TOMCHAT"));
    brand.add_css_class("title-1");
    sidebar.append(&brand);
    let nav_contacts = gtk::Button::with_label("◎   Contacts");
    let nav_chats = gtk::Button::with_label("▤   Chats");
    let nav_settings = gtk::Button::with_label("⚙   Settings");
    nav_contacts.add_css_class("flat");
    nav_chats.add_css_class("flat");
    nav_settings.add_css_class("flat");
    sidebar.append(&nav_contacts);
    sidebar.append(&nav_chats);
    sidebar.append(&nav_settings);
    let sidebar_note = gtk::Label::new(Some("DIRECT PEER CHAT"));
    sidebar_note.add_css_class("dim-label");
    sidebar_note.set_wrap(true);
    sidebar_note.set_xalign(0.0);
    sidebar.append(&sidebar_note);
    let nav_stack = stack.clone();
    nav_contacts.connect_clicked(move |_| nav_stack.set_visible_child_name("contacts"));
    let nav_stack = stack.clone();
    nav_chats.connect_clicked(move |_| nav_stack.set_visible_child_name("chats"));
    let nav_stack = stack.clone();
    nav_settings.connect_clicked(move |_| nav_stack.set_visible_child_name("settings"));

    let shell = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    shell.append(&sidebar);
    let divider = gtk::Separator::new(gtk::Orientation::Vertical);
    shell.append(&divider);
    shell.append(&stack);

    let page = adw::ToolbarView::new();
    page.add_top_bar(&header);
    page.set_content(Some(&shell));
    window.set_content(Some(&page));

    let qr_status = status.clone();
    let qr_card_view = own_card.clone();
    let qr_parent = window.clone();
    show_qr.connect_clicked(move |_| {
        let buffer = qr_card_view.buffer();
        let encoded = buffer.text(&buffer.start_iter(), &buffer.end_iter(), false).to_string();
        if !encoded.starts_with("TOMCHAT-CONTACT-V2.") {
            qr_status.set_text("Create or recover your device identity before displaying a QR code.");
            return;
        }
        match qr::write_png(encoded.trim()) {
            Ok(path) => {
                let qr_window = gtk::Window::builder()
                    .title("Your TomChat contact QR code")
                    .transient_for(&qr_parent)
                    .modal(true)
                    .default_width(440)
                    .default_height(520)
                    .build();
                let panel = gtk::Box::new(gtk::Orientation::Vertical, 12);
                panel.set_margin_top(20);
                panel.set_margin_bottom(20);
                panel.set_margin_start(20);
                panel.set_margin_end(20);
                let picture = gtk::Picture::for_filename(&path);
                picture.set_can_shrink(true);
                picture.set_size_request(360, 360);
                panel.append(&picture);
                let hint = gtk::Label::new(Some("Let another TomChat user scan this code, then compare fingerprints before trusting the contact."));
                hint.set_wrap(true);
                panel.append(&hint);
                let close = gtk::Button::with_label("Close");
                let close_window = qr_window.clone();
                close.connect_clicked(move |_| close_window.close());
                panel.append(&close);
                qr_window.set_child(Some(&panel));
                let cleanup = path.clone();
                qr_window.connect_close_request(move |_| {
                    let _ = std::fs::remove_file(&cleanup);
                    glib::Propagation::Proceed
                });
                qr_window.present();
            }
            Err(error) => qr_status.set_text(&format!("Could not generate contact QR code: {error}")),
        }
    });

    let scan_status = status.clone();
    let scan_parent = window.clone();
    let scan_input = import_input.clone();
    let scan_confirm = fingerprint_confirm.clone();
    scan_qr.connect_clicked(move |_| {
        let chooser = gtk::FileChooserNative::new(
            Some("Choose an image containing a TomChat QR code"),
            Some(&scan_parent),
            gtk::FileChooserAction::Open,
            Some("Open"),
            Some("Cancel"),
        );
        let input = scan_input.clone();
        let status = scan_status.clone();
        let confirm = scan_confirm.clone();
        chooser.connect_response(move |dialog, response| {
            if response == gtk::ResponseType::Accept {
                if let Some(path) = dialog.file().and_then(|file| file.path()) {
                    match qr::decode_image(&path) {
                        Ok(payload) => {
                            input.buffer().set_text(&payload);
                            confirm.set_active(false);
                            status.set_text("QR decoded. Compare the displayed fingerprint with the person, tick the confirmation box, then verify and trust the contact.");
                        }
                        Err(error) => status.set_text(&format!("Could not read a TomChat QR code from that image: {error}")),
                    }
                }
            }
            dialog.destroy();
        });
        chooser.show();
    });

    let webcam_status = status.clone();
    let webcam_input = import_input.clone();
    let webcam_confirm = fingerprint_confirm.clone();
    let webcam_button = webcam_qr.clone();
    webcam_qr.connect_clicked(move |_| {
        webcam_button.set_sensitive(false);
        webcam_status.set_text("Opening your webcam for a QR code…");
        let (tx, rx) = mpsc::channel::<Result<String, String>>();
        std::thread::spawn(move || {
            let _ = tx.send(qr::scan_webcam_once());
        });
        let status = webcam_status.clone();
        let input = webcam_input.clone();
        let confirm = webcam_confirm.clone();
        let button = webcam_button.clone();
        glib::timeout_add_local(Duration::from_millis(100), move || {
            match rx.try_recv() {
                Ok(Ok(payload)) => {
                    input.buffer().set_text(&payload);
                    confirm.set_active(false);
                    status.set_text("Webcam QR decoded. Compare the fingerprint with the person, then confirm before trusting the contact.");
                    button.set_sensitive(true);
                    glib::ControlFlow::Break
                }
                Ok(Err(error)) => {
                    status.set_text(&format!("Webcam QR scan failed: {error}"));
                    button.set_sensitive(true);
                    glib::ControlFlow::Break
                }
                Err(mpsc::TryRecvError::Empty) => glib::ControlFlow::Continue,
                Err(mpsc::TryRecvError::Disconnected) => {
                    status.set_text("Webcam QR scanner stopped unexpectedly.");
                    button.set_sensitive(true);
                    glib::ControlFlow::Break
                }
            }
        });
    });

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
                    Ok(peer_chat::PeerChatEvent::Text(text)) => {
                        append_chat_line(&poll_view, "Peer", &text)
                    }
                    Ok(peer_chat::PeerChatEvent::GroupProtocol(frame)) => {
                        poll_status.set_text(&format!(
                            "Received an MLS group {:?} frame for group {} at epoch {}. Group processing is not enabled until the MLS engine is integrated.",
                            frame.kind,
                            frame.group_id.iter().map(|byte| format!("{byte:02x}")).collect::<String>(),
                            frame.epoch
                        ));
                    }
                    Ok(peer_chat::PeerChatEvent::DeliveryConfirmed(message_id)) => {
                        let id = message_id.iter().map(|byte| format!("{byte:02x}")).collect::<String>();
                        poll_status.set_text(&format!("Peer confirmed delivery of message {id}."));
                    }
                    Ok(peer_chat::PeerChatEvent::UnsupportedFrame(frame_type)) => {
                        poll_status.set_text(&format!("Received an unsupported peer frame: {frame_type:?}."));
                    }
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
