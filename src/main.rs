use adw::prelude::*;
use adw::Application;

fn main() {
    let app = Application::builder()
        .application_id("uk.co.tomserver.TomChat")
        .build();

    app.connect_activate(|app| {
        let window = adw::ApplicationWindow::builder()
            .application(app)
            .title("TomChat")
            .default_width(900)
            .default_height(640)
            .build();

        let header = adw::HeaderBar::new();
        let title = gtk::Label::new(Some("TomChat"));
        title.add_css_class("title-1");

        let content = gtk::Box::new(gtk::Orientation::Vertical, 12);
        content.set_margin_top(32);
        content.set_margin_bottom(32);
        content.set_margin_start(32);
        content.set_margin_end(32);
        content.append(&title);
        content.append(&gtk::Label::new(Some(
            "Welcome to TomChat. Server connection and sign-in are coming next.",
        )));

        let page = adw::ToolbarView::new();
        page.add_top_bar(&header);
        page.set_content(Some(&content));
        window.set_content(Some(&page));
        window.present();
    });

    app.run();
}
