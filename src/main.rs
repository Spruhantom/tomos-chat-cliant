mod contact_card;
mod contact_store;

use adw::prelude::*;
use gtk::prelude::*;
use std::cell::RefCell;
use std::rc::Rc;

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
        .default_width(620)
        .default_height(720)
        .build();

    let header = adw::HeaderBar::new();
    let title = gtk::Label::new(Some("Your contacts"));
    title.add_css_class("title-1");

    let status = gtk::Label::new(None);
    status.set_wrap(true);
    status.set_xalign(0.0);

    let identity_status = match contact_card::SigningIdentity::load_from_secret_service() {
        Ok(_) => "Your P-256 signing identity is loaded from your desktop keyring.".to_string(),
        Err(_) => match contact_card::SigningIdentity::generate() {
            Ok(identity) => match identity.save_to_secret_service() {
                Ok(()) => "A new P-256 signing identity has been saved in your desktop keyring.".to_string(),
                Err(error) => format!("Secure identity setup failed: {error}"),
            },
            Err(error) => format!("Identity setup failed: {error}"),
        },
    };

    let store = Rc::new(contact_store::TrustedContactStore::default());
    let contacts = Rc::new(RefCell::new(store.load().unwrap_or_default()));

    let card_input = gtk::TextView::builder()
        .wrap_mode(gtk::WrapMode::WordChar)
        .top_margin(8).bottom_margin(8).left_margin(8).right_margin(8)
        .height_request(110)
        .build();
    card_input.set_vexpand(false);
    let card_scroll = gtk::ScrolledWindow::builder()
        .child(&card_input)
        .min_content_height(110)
        .build();

    let import = gtk::Button::with_label("Import signed contact card");
    import.add_css_class("suggested-action");
    let contact_list = gtk::ListBox::new();
    contact_list.add_css_class("boxed-list");
    contact_list.set_selection_mode(gtk::SelectionMode::None);
    let list_scroll = gtk::ScrolledWindow::builder()
        .child(&contact_list)
        .vexpand(true)
        .build();

    let content = gtk::Box::new(gtk::Orientation::Vertical, 14);
    content.set_margin_top(24);
    content.set_margin_bottom(24);
    content.set_margin_start(24);
    content.set_margin_end(24);
    content.append(&title);

    let identity_label = gtk::Label::new(Some(&identity_status));
    identity_label.set_wrap(true);
    identity_label.set_xalign(0.0);
    content.append(&identity_label);

    let import_heading = gtk::Label::new(Some("Add a contact"));
    import_heading.add_css_class("heading");
    import_heading.set_xalign(0.0);
    content.append(&import_heading);
    let instructions = gtk::Label::new(Some(
        "Paste a TomChat V2 contact card. Verify the fingerprint with the person before trusting it. Legacy V1 cards can be imported for reference but cannot start encrypted peer sessions."
    ));
    instructions.set_wrap(true);
    instructions.set_xalign(0.0);
    content.append(&instructions);
    content.append(&card_scroll);
    content.append(&import);
    content.append(&status);

    let contacts_heading = gtk::Label::new(Some("Trusted contacts"));
    contacts_heading.add_css_class("heading");
    contacts_heading.set_xalign(0.0);
    content.append(&contacts_heading);
    content.append(&list_scroll);

    let page = adw::ToolbarView::new();
    page.add_top_bar(&header);
    page.set_content(Some(&content));
    window.set_content(Some(&page));

    refresh_contact_list(&contact_list, &contacts.borrow());

    let store_for_import = store.clone();
    let contacts_for_import = contacts.clone();
    let list_for_import = contact_list.clone();
    let input_for_import = card_input.clone();
    let status_for_import = status.clone();
    import.connect_clicked(move |_| {
        let buffer = input_for_import.buffer();
        let start = buffer.start_iter();
        let end = buffer.end_iter();
        let encoded = buffer.text(&start, &end, false).to_string();
        if encoded.trim().is_empty() {
            status_for_import.set_text("Paste a signed contact card first.");
            return;
        }
        match store_for_import.import(encoded.trim()) {
            Ok(updated) => {
                *contacts_for_import.borrow_mut() = updated;
                refresh_contact_list(&list_for_import, &contacts_for_import.borrow());
                buffer.set_text("");
                status_for_import.set_text("Contact card verified and saved in your desktop keyring. Compare its fingerprint out of band before starting a conversation.");
            }
            Err(error) => status_for_import.set_text(&format!("Contact was not imported: {error}")),
        }
    });

    window.present();
}

fn refresh_contact_list(list: &gtk::ListBox, contacts: &[contact_card::ContactCard]) {
    while let Some(child) = list.first_child() {
        list.remove(&child);
    }
    if contacts.is_empty() {
        let empty = gtk::Label::new(Some("No contacts yet. Import a signed contact card to get started."));
        empty.set_margin_top(18);
        empty.set_margin_bottom(18);
        list.append(&empty);
        return;
    }
    for contact in contacts {
        let row = gtk::Box::new(gtk::Orientation::Horizontal, 12);
        row.set_margin_top(10);
        row.set_margin_bottom(10);
        row.set_margin_start(12);
        row.set_margin_end(12);
        let details = gtk::Box::new(gtk::Orientation::Vertical, 4);
        details.set_hexpand(true);
        let name = gtk::Label::new(Some(&contact.display_name));
        name.set_xalign(0.0);
        name.add_css_class("heading");
        let trust = if contact.is_noise_compatible() {
            "Signed V2 identity · Noise key bound"
        } else {
            "Legacy V1 identity · encrypted sessions unavailable"
        };
        let subtitle = gtk::Label::new(Some(&format!("{trust}\n{}", contact.fingerprint)));
        subtitle.set_xalign(0.0);
        subtitle.set_wrap(true);
        subtitle.add_css_class("dim-label");
        details.append(&name);
        details.append(&subtitle);
        row.append(&details);
        list.append(&row);
    }
}
