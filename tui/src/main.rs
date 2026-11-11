mod app;
mod config;
mod ui;

use anyhow::Result;
use app::App;
use crossterm::{
    event::{self, Event, KeyCode, KeyEventKind},
    execute,
    terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen},
};
use ratatui::{backend::CrosstermBackend, Terminal};
use std::{io, time::Duration};

fn main() -> Result<()> {
    let mut app = App::load()?;
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;

    let result = run(&mut terminal, &mut app);

    disable_raw_mode()?;
    execute!(terminal.backend_mut(), LeaveAlternateScreen)?;
    terminal.show_cursor()?;
    result
}

fn run(
    terminal: &mut Terminal<CrosstermBackend<io::Stdout>>,
    app: &mut App,
) -> Result<()> {
    while !app.should_quit {
        terminal.draw(|frame| ui::draw(frame, app))?;
        if event::poll(Duration::from_millis(150))? {
            if let Event::Key(key) = event::read()? {
                if key.kind == KeyEventKind::Press {
                    match key.code {
                        KeyCode::Char(ch) if app.input_mode => app.input.push(ch),
                        KeyCode::Backspace if app.input_mode => { app.input.pop(); }
                        KeyCode::Enter if app.input_mode => app.submit_input(),
                        KeyCode::Esc => app.input_mode = false,
                        KeyCode::Char('q') if !app.input_mode => app.should_quit = true,
                        KeyCode::Char('/') if !app.input_mode => app.input_mode = true,
                        KeyCode::Tab if !app.input_mode => app.next_panel(),
                        KeyCode::Up if !app.input_mode => app.previous_item(),
                        KeyCode::Down if !app.input_mode => app.next_item(),
                        KeyCode::F(1) => app.show_help = !app.show_help,
                        _ => {}
                    }
                }
            }
        }
    }
    app.save()?;
    Ok(())
}
