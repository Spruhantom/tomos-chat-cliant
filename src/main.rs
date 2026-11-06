use adw::prelude::*;
use adw::Application;
use gtk::glib;
use std::net::IpAddr;
use std::str::FromStr;
use std::sync::mpsc;
use std::time::Duration;

fn validate_server_url(input: &str) -> Result<String, String> {
    let parsed = url::Url::parse(input.trim())
        .map_err(|_| "Enter a valid server URL, including https://".to_string())?;
    let host = parsed
        .host_str()
        .ok_or_else(|| "The server URL must include a hostname.".to_string())?;
    let is_loopback = host.eq_ignore_ascii_case("localhost")
        || IpAddr::from_str(host)
            .map(|address| address.is_loopback())
            .unwrap_or(false);

    if !parsed.username().is_empty()
        || parsed.password().is_some()
        || parsed.query().is_some()
        || parsed.fragment().is_some()
        || (parsed.path() != "/" && !parsed.path().is_empty())
    {
        return Err("Enter only the server's base URL, without credentials, a path, query or fragment.".to_string());
    }

    match parsed.scheme() {
        "https" => {}
        "http" if is_loopback => {}
        "http" => {
            return Err(
                "Use HTTPS for remote servers. HTTP is allowed only for localhost or a loopback IP address."
                    .to_string(),
            )
        }
        _ => return Err("The server URL must use HTTPS, or HTTP for local development.".to_string()),
    }

    Ok(parsed.as_str().trim_end_matches('/').to_string())
}

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
                glib::ControlFlow::Continue
            }
            Ok(Err(message)) => {
                status_for_poll.set_text(&message);
                login_for_poll.set_sensitive(true);
                glib::ControlFlow::Continue
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
        let server_text = server_entry.text();
        let base = match validate_server_url(server_text.as_str()) {
            Ok(value) => value,
            Err(message) => {
                status_for_click.set_text(&message);
                return;
            }
        };
        let user = username_entry.text().to_string();
        let pass = password_entry.text().to_string();
        if base.is_empty() || user.is_empty() || pass.is_empty() {
            status_for_click.set_text("Enter the server address, username and password.");
            return;
        }
        button.set_sensitive(false);
        status_for_click.set_text("Connecting…");
        let sender = tx.clone();
        std::thread::spawn(move || {
            let agent = ureq::AgentBuilder::new()
                .timeout(Duration::from_secs(20))
                .timeout_connect(Duration::from_secs(5))
                .timeout_read(Duration::from_secs(15))
                .timeout_write(Duration::from_secs(15))
                .redirects(0)
                .build();
            let result = agent
                .post(&format!("{base}/api/v1/auth/login"))
                .send_json(&Credentials {
                    username: &user,
                    password: &pass,
                })
                .map_err(|error| format!("Sign-in failed: {error}"))
                .and_then(|response| {
                    response
                        .into_json::<LoginResponse>()
                        .map_err(|error| format!("Invalid server response: {error}"))
                })
                .map(|reply| {
                    // Token is deliberately not persisted until OS-keyring integration is added.
                    let _session_token = reply.token;
                    format!(
                        "Signed in as {}. Secure token storage is coming next.",
                        reply.user.username
                    )
                });
            let _ = sender.send(result);
        });
    });

    window.present();
}


#[cfg(test)]
mod tests {
    use super::validate_server_url;

    #[test]
    fn accepts_https_remote_server() {
        assert_eq!(
            validate_server_url("https://chat.example.com/").unwrap(),
            "https://chat.example.com"
        );
    }

    #[test]
    fn accepts_exact_localhost_and_loopback_addresses() {
        assert!(validate_server_url("http://localhost:8080").is_ok());
        assert!(validate_server_url("http://127.0.0.1:8080").is_ok());
        assert!(validate_server_url("http://[::1]:8080").is_ok());
    }

    #[test]
    fn rejects_http_for_remote_hosts_and_localhost_lookalikes() {
        assert!(validate_server_url("http://chat.example.com").is_err());
        assert!(validate_server_url("http://localhost.attacker.example").is_err());
        assert!(validate_server_url("http://127.0.0.1.attacker.example").is_err());
    }

    #[test]
    fn rejects_credentials_paths_queries_and_fragments() {
        assert!(validate_server_url("https://user:pass@chat.example.com").is_err());
        assert!(validate_server_url("https://chat.example.com/api").is_err());
        assert!(validate_server_url("https://chat.example.com/?x=1").is_err());
        assert!(validate_server_url("https://chat.example.com/#fragment").is_err());
    }

    #[test]
    fn rejects_unsupported_schemes() {
        assert!(validate_server_url("ftp://chat.example.com").is_err());
        assert!(validate_server_url("file:///tmp/tomchat").is_err());
    }
}
