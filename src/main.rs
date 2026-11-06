use adw::prelude::*;
use adw::Application;
use gtk::glib;
use std::sync::mpsc;
use std::time::Duration;

#[derive(serde::Serialize)]
struct Credentials<'a> {
    username: &'a str,
    password: &'a str,
}

#[derive(serde::Deserialize)]
struct LoginResponse {
    user: LoginUser,
    token: String,
}

#[derive(serde::Deserialize)]
struct LoginUser {
    username: String,
}

fn main() {
    let app = Application::builder()
        .application_id("uk.co.tomserver.TomChat")
        .build();

    app.connect_activate(build_login);
    app.run();
}

fn build_login(app: &Application) {
    let window = adw::ApplicationWindow::builder()
        .application(app)
        .title("TomChat")
        .default_width(460)
        .default_height(560)
        .build();

    let header = adw::HeaderBar::new();
    let server = gtk::Entry::builder()
        .placeholder_text("https://chat.example.com")
        .text("http://127.0.0.1:8080")
        .hexpand(true)
        .build();
    let username = gtk::Entry::builder()
        .placeholder_text("Username")
        .hexpand(true)
        .build();
    let password = gtk::PasswordEntry::builder()
        .placeholder_text("Password")
        .show_peek_icon(true)
        .hexpand(true)
        .build();
    let status = gtk::Label::new(Some("Connect to your TomChat server."));
    status.set_wrap(true);
    let login = gtk::Button::with_label("Sign in");
    login.add_css_class("suggested-action");

    let content = gtk::Box::new(gtk::Orientation::Vertical, 14);
    content.set_margin_top(36);
    content.set_margin_bottom(36);
    content.set_margin_start(32);
    content.set_margin_end(32);
    let title = gtk::Label::new(Some("Welcome to TomChat"));
    title.add_css_class("title-1");
    content.append(&title);
    content.append(&gtk::Label::new(Some("Server address")));
    content.append(&server);
    content.append(&gtk::Label::new(Some("Username")));
    content.append(&username);
    content.append(&gtk::Label::new(Some("Password")));
    content.append(&password);
    content.append(&login);
    content.append(&status);

    let page = adw::ToolbarView::new();
    page.add_top_bar(&header);
    page.set_content(Some(&content));
    window.set_content(Some(&page));

    let (tx, rx) = mpsc::channel::<Result<String, String>>();
    let status_for_poll = status.clone();
    let login_for_poll = login.clone();
    glib::timeout_add_local(Duration::from_millis(100), move || {
        match rx.try_recv() {
            Ok(Ok(message)) => {
                status_for_poll.set_text(&message);
                login_for_poll.set_sensitive(true);
                glib::ControlFlow::Break
            }
            Ok(Err(message)) => {
                status_for_poll.set_text(&message);
                login_for_poll.set_sensitive(true);
                glib::ControlFlow::Break
            }
            Err(mpsc::TryRecvError::Empty) => glib::ControlFlow::Continue,
            Err(mpsc::TryRecvError::Disconnected) => glib::ControlFlow::Break,
        }
    });

    let server_entry = server.clone();
    let username_entry = username.clone();
    let password_entry = password.clone();
    let status_for_click = status.clone();
    login.connect_clicked(move |button| {
        let base = server_entry.text().trim().trim_end_matches('/').to_string();
        let user = username_entry.text().to_string();
        let pass = password_entry.text().to_string();
        if base.is_empty() || user.is_empty() || pass.is_empty() {
            status_for_click.set_text("Enter the server address, username and password.");
            return;
        }
        if !(base.starts_with("https://") || base.starts_with("http://127.0.0.1") || base.starts_with("http://localhost")) {
            status_for_click.set_text("Use HTTPS for remote servers. HTTP is allowed only for local development.");
            return;
        }

        button.set_sensitive(false);
        status_for_click.set_text("Connecting…");
        let sender = tx.clone();
        std::thread::spawn(move || {
            let result = ureq::post(&format!("{base}/api/v1/auth/login"))
                .send_json(serde_json::to_value(Credentials { username: &user, password: &pass }).unwrap_or_default())
                .map_err(|error| format!("Sign-in failed: {error}"))
                .and_then(|response| response.into_json::<LoginResponse>().map_err(|error| format!("Invalid server response: {error}")))
                .map(|reply| {
                    // Token is deliberately not persisted until OS-keyring integration is added.
                    let _session_token = reply.token;
                    format!("Signed in as {}. Secure token storage is coming next.", reply.user.username)
                });
            let _ = sender.send(result);
        });
    });

    window.present();
}
