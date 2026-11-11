use crate::app::App;
use ratatui::{
    layout::{Constraint, Direction, Layout, Rect},
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Block, Borders, Clear, List, ListItem, Paragraph, Wrap},
    Frame,
};

const CYAN: Color = Color::Rgb(0, 210, 255);
const BLUE: Color = Color::Rgb(30, 100, 255);
const MUTED: Color = Color::Rgb(120, 145, 165);

pub fn draw(frame: &mut Frame, app: &App) {
    let area = frame.area();
    let root = Layout::default().direction(Direction::Vertical)
        .constraints([Constraint::Length(3), Constraint::Min(8), Constraint::Length(3), Constraint::Length(1)])
        .split(area);

    let header = Paragraph::new(Line::from(vec![
        Span::styled(" TOMCHAT ", Style::default().fg(Color::Black).bg(CYAN).add_modifier(Modifier::BOLD)),
        Span::styled("  DECENTRALISED MESSENGER  ", Style::default().fg(CYAN).add_modifier(Modifier::BOLD)),
        Span::styled("  ZERO SERVER  ", Style::default().fg(Color::Black).bg(BLUE).add_modifier(Modifier::BOLD)),
    ])).block(Block::default().borders(Borders::BOTTOM).border_style(Style::default().fg(CYAN)));
    frame.render_widget(header, root[0]);

    let columns = Layout::default().direction(Direction::Horizontal)
        .constraints([Constraint::Percentage(28), Constraint::Percentage(72)])
        .split(root[1]);

    let peers = app.config.peers.iter().enumerate().map(|(i, peer)| {
        let marker = if i == app.selected_peer { "› " } else { "  " };
        ListItem::new(format!("{marker}{}", peer.name))
    }).collect::<Vec<_>>();
    let peer_block = List::new(peers)
        .block(Block::default().title(" CONTACTS ").borders(Borders::ALL).border_style(Style::default().fg(if app.active_panel == 1 { CYAN } else { MUTED })))
        .highlight_style(Style::default().fg(CYAN).add_modifier(Modifier::BOLD));
    frame.render_widget(peer_block, columns[0]);

    let chat = Layout::default().direction(Direction::Vertical)
        .constraints([Constraint::Min(4), Constraint::Length(3)])
        .split(columns[1]);
    let mut lines = app.messages.iter().map(|message| Line::from(Span::styled(message.as_str(), Style::default().fg(Color::White)))).collect::<Vec<_>>();
    if app.config.peers.is_empty() {
        lines.push(Line::from(Span::styled("No trusted contacts saved yet.", Style::default().fg(MUTED))));
    }
    let history = Paragraph::new(lines).wrap(Wrap { trim: true })
        .block(Block::default().title(" CONVERSATION ").borders(Borders::ALL).border_style(Style::default().fg(if app.active_panel == 0 { CYAN } else { MUTED })));
    frame.render_widget(history, chat[0]);

    let prompt = if app.input_mode { format!("> {}", app.input) } else { "Press / to enter a command or message · F1 for help".into() };
    let composer = Paragraph::new(prompt).block(Block::default().title(" INPUT ").borders(Borders::ALL).border_style(Style::default().fg(CYAN)));
    frame.render_widget(composer, chat[1]);

    let status = Paragraph::new(app.status.as_str())
        .style(Style::default().fg(Color::Yellow))
        .block(Block::default().borders(Borders::LEFT | Borders::RIGHT).border_style(Style::default().fg(BLUE)));
    frame.render_widget(status, root[2]);
    frame.render_widget(Paragraph::new("F1 Help  ·  TAB Switch panel  ·  ↑/↓ Select contact  ·  / Command  ·  Q Quit").style(Style::default().fg(MUTED)), root[3]);

    if app.show_help {
        let popup = centered_rect(76, 72, area);
        frame.render_widget(Clear, popup);
        let help = Paragraph::new(vec![
            Line::from(Span::styled("TomChat TUI — commands", Style::default().fg(CYAN).add_modifier(Modifier::BOLD))),
            Line::from(""),
            Line::from("/help                         Show this help"),
            Line::from("/peers                        Open contacts"),
            Line::from("/chats                        Open conversations"),
            Line::from("/settings                     Open settings"),
            Line::from("/add-peer <name> <sha256> <host:port>  Save a peer hint"),
            Line::from("/listen                       (transport not enabled yet)"),
            Line::from("/connect                     (transport not enabled yet)"),
            Line::from("/quit                         Exit TomChat"),
            Line::from(""),
            Line::from(Span::styled("Security notice", Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD))),
            Line::from("This milestone is a UI and local configuration foundation."),
            Line::from("It does not send messages or establish peer connections."),
            Line::from("Do not enter secrets or treat a saved peer as verified."),
            Line::from("No server, relay, hosted discovery or cloud service is contacted."),
        ]).wrap(Wrap { trim: true }).block(Block::default().title(" HELP ").borders(Borders::ALL).border_style(Style::default().fg(CYAN)));
        frame.render_widget(help, popup);
    }
}

fn centered_rect(percent_x: u16, percent_y: u16, area: Rect) -> Rect {
    let vertical = Layout::default().direction(Direction::Vertical)
        .constraints([Constraint::Percentage((100 - percent_y) / 2), Constraint::Percentage(percent_y), Constraint::Percentage((100 - percent_y) / 2)])
        .split(area);
    Layout::default().direction(Direction::Horizontal)
        .constraints([Constraint::Percentage((100 - percent_x) / 2), Constraint::Percentage(percent_x), Constraint::Percentage((100 - percent_x) / 2)])
        .split(vertical[1])[1]
}
