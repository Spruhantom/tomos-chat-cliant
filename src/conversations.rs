//! GTK4 conversation browser backed by the TomChat REST API.
//!
//! Messages are shown as opaque encrypted envelopes. This UI does not decrypt,
//! compose plaintext, or claim end-to-end encryption.
use crate::messaging::TomChatMessaging;
use adw::prelude::*;
use gtk::glib;
use std::sync::mpsc;
use std::time::Duration;

enum UiEvent {
    Conversations(Result<Vec<crate::messaging::Conversation>, String>),
    History(Result<Vec<crate::messaging::Message>, String>),
}

pub fn show(app: &adw::Application, base_url: String, token: String, username: String) {
    let window = adw::ApplicationWindow::builder()
        .application(app)
        .title("TomChat")
        .default_width(980)
        .default_height(680)
        .build();

    let header = adw::HeaderBar::new();
    let identity = gtk::Label::new(Some(&format!("Signed in as {username}")));
    header.pack_end(&identity);

    let sidebar = gtk::Box::new(gtk::Orientation::Vertical, 8);
    sidebar.set_margin_top(12);
    sidebar.set_margin_bottom(12);
    sidebar.set_margin_start(12);
    sidebar.set_margin_end(12);
    sidebar.set_size_request(260, -1);
    let conversations_title = gtk::Label::new(Some("Conversations"));
    conversations_title.add_css_class("title-2");
    conversations_title.set_halign(gtk::Align::Start);
    let refresh = gtk::Button::with_label("Refresh");
    let conversation_list = gtk::ListBox::new();
    conversation_list.set_selection_mode(gtk::SelectionMode::Single);
    conversation_list.add_css_class("boxed-list");
    let sidebar_scroll = gtk::ScrolledWindow::builder()
        .vexpand(true)
        .child(&conversation_list)
        .build();
    sidebar.append(&conversations_title);
    sidebar.append(&refresh);
    sidebar.append(&sidebar_scroll);

    let transcript = gtk::Box::new(gtk::Orientation::Vertical, 10);
    transcript.set_margin_top(18);
    transcript.set_margin_bottom(18);
    transcript.set_margin_start(18);
    transcript.set_margin_end(18);
    let conversation_heading = gtk::Label::new(Some("Choose a conversation"));
    conversation_heading.add_css_class("title-2");
    conversation_heading.set_halign(gtk::Align::Start);
    let security_notice = gtk::Label::new(Some(
        "Messages are currently displayed as opaque server-stored envelopes. End-to-end encryption and secure message composition are not yet implemented.",
    ));
    security_notice.set_wrap(true);
    security_notice.add_css_class("dim-label");
    security_notice.set_halign(gtk::Align::Start);
    let message_list = gtk::ListBox::new();
    message_list.set_selection_mode(gtk::SelectionMode::None);
    message_list.add_css_class("boxed-list");
    let message_scroll = gtk::ScrolledWindow::builder()
        .vexpand(true)
        .child(&message_list)
        .build();
    let composer = gtk::Entry::builder()
        .placeholder_text("Message composition will be enabled after client-side encryption is implemented")
        .hexpand(true)
        .sensitive(false)
        .build();
    let send = gtk::Button::with_label("Send");
    send.set_sensitive(false);
    let compose_row = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    compose_row.append(&composer);
    compose_row.append(&send);
    transcript.append(&conversation_heading);
    transcript.append(&security_notice);
    transcript.append(&message_scroll);
    transcript.append(&compose_row);

    let split = gtk::Paned::new(gtk::Orientation::Horizontal);
    split.set_start_child(Some(&sidebar));
    split.set_end_child(Some(&transcript));
    split.set_resize_start_child(false);
    split.set_shrink_start_child(false);
    split.set_position(280);
    let page = adw::ToolbarView::new();
    page.add_top_bar(&header);
    page.set_content(Some(&split));
    window.set_content(Some(&page));

    let (tx, rx) = mpsc::channel::<UiEvent>();
    let base_for_refresh = base_url.clone();
    let token_for_refresh = token.clone();
    let tx_refresh = tx.clone();
    let load_conversations = move || {
        let base = base_for_refresh.clone();
        let token = token_for_refresh.clone();
        let sender = tx_refresh.clone();
        std::thread::spawn(move || {
            let result = TomChatMessaging::new(&base, &token)
                .and_then(|api| api.list_conversations().map(|page| page.conversations));
            let _ = sender.send(UiEvent::Conversations(result));
        });
    };
    load_conversations();
    let refresh_action = load_conversations.clone();
    refresh.connect_clicked(move |_| refresh_action());

    let conversation_ids = std::rc::Rc::new(std::cell::RefCell::new(Vec::<String>::new()));
    let ids_for_poll = conversation_ids.clone();
    let list_for_poll = conversation_list.clone();
    let messages_for_poll = message_list.clone();
    let heading_for_poll = conversation_heading.clone();
    glib::timeout_add_local(Duration::from_millis(100), move || {
        loop {
            match rx.try_recv() {
                Ok(UiEvent::Conversations(Ok(items))) => {
                    while let Some(child) = list_for_poll.first_child() {
                        list_for_poll.remove(&child);
                    }
                    ids_for_poll.borrow_mut().clear();
                    for item in items {
                        let label = item.title.clone().filter(|value| !value.trim().is_empty())
                            .unwrap_or_else(|| {
                                let peers = item.member_ids.iter().take(3)
                                    .map(|id| id.chars().take(8).collect::<String>())
                                    .collect::<Vec<_>>().join(", ");
                                format!("{} · {}", item.kind, peers)
                            });
                        let row_label = gtk::Label::new(Some(&label));
                        row_label.set_halign(gtk::Align::Start);
                        row_label.set_margin_top(10);
                        row_label.set_margin_bottom(10);
                        row_label.set_margin_start(10);
                        row_label.set_margin_end(10);
                        let row = gtk::ListBoxRow::new();
                        row.set_child(Some(&row_label));
                        ids_for_poll.borrow_mut().push(item.id);
                        list_for_poll.append(&row);
                    }
                }
                Ok(UiEvent::Conversations(Err(error))) => {
                    heading_for_poll.set_text(&format!("Could not load conversations: {error}"));
                }
                Ok(UiEvent::History(Ok(items))) => {
                    while let Some(child) = messages_for_poll.first_child() {
                        messages_for_poll.remove(&child);
                    }
                    for message in items {
                        let bytes_estimate = message.ciphertext.len().saturating_mul(3) / 4;
                        let line = format!(
                            "Message #{} · {} · encrypted payload (about {} bytes)",
                            message.sequence,
                            message.sender_id.chars().take(8).collect::<String>(),
                            bytes_estimate
                        );
                        let label = gtk::Label::new(Some(&line));
                        label.set_halign(gtk::Align::Start);
                        label.set_wrap(true);
                        label.set_margin_top(8);
                        label.set_margin_bottom(8);
                        label.set_margin_start(10);
                        label.set_margin_end(10);
                        let row = gtk::ListBoxRow::new();
                        row.set_child(Some(&label));
                        messages_for_poll.append(&row);
                    }
                }
                Ok(UiEvent::History(Err(error))) => {
                    heading_for_poll.set_text(&format!("Could not load message history: {error}"));
                }
                Err(mpsc::TryRecvError::Empty) => break,
                Err(mpsc::TryRecvError::Disconnected) => return glib::ControlFlow::Break,
            }
        }
        glib::ControlFlow::Continue
    });

    let base_for_history = base_url;
    let token_for_history = token;
    let ids_for_selection = conversation_ids;
    let sender = tx;
    let heading_for_selection = conversation_heading.clone();
    conversation_list.connect_row_selected(move |_, row| {
        let Some(row) = row else { return };
        let index = row.index();
        let Some(conversation_id) = ids_for_selection.borrow().get(index as usize).cloned() else {
            return;
        };
        heading_for_selection.set_text("Loading conversation…");
        let base = base_for_history.clone();
        let token = token_for_history.clone();
        let sender = sender.clone();
        std::thread::spawn(move || {
            let result = TomChatMessaging::new(&base, &token)
                .and_then(|api| api.list_messages(&conversation_id, 100, None)
                    .map(|page| page.messages));
            let _ = sender.send(UiEvent::History(result));
        });
    });

    window.present();
}
