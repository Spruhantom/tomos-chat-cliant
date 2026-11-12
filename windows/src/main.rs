#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[cfg(not(target_os = "windows"))]
compile_error!("TomChat Windows must be built for a Windows target.");

mod identity_store;

use std::ptr::null;
use std::sync::atomic::{AtomicIsize, Ordering};
use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetMessageW, LoadCursorW, PostQuitMessage,
    RegisterClassW, SetWindowTextW, ShowWindow, TranslateMessage, BS_PUSHBUTTON, CW_USEDEFAULT,
    IDC_ARROW, MSG, SW_SHOW, WM_COMMAND, WM_DESTROY, WNDCLASSW, WS_BORDER, WS_CHILD, WS_OVERLAPPEDWINDOW,
    WS_TABSTOP, WS_VISIBLE,
};

const WINDOW_CLASS: &[u16] = &[84, 111, 109, 67, 104, 97, 116, 87, 105, 110, 100, 111, 119, 0];
const CREATE_ID: usize = 1001;
const LOAD_ID: usize = 1002;
static STATUS_WINDOW: AtomicIsize = AtomicIsize::new(0);

unsafe extern "system" fn window_proc(
    hwnd: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        WM_COMMAND => {
            let command_id = wparam & 0xffff;
            let status_hwnd = STATUS_WINDOW.load(Ordering::Relaxed) as HWND;
            if command_id == CREATE_ID || command_id == LOAD_ID {
                let outcome = if command_id == CREATE_ID {
                    identity_store::IdentityBundle::create()
                        .map(|identity| {
                            drop(identity);
                            "New TomChat identity created and protected for this Windows user."
                                .to_string()
                        })
                } else {
                    identity_store::IdentityBundle::load()
                        .map(|identity| {
                            drop(identity);
                            "Existing TomChat identity loaded successfully."
                                .to_string()
                        })
                };
                let message = match outcome {
                    Ok(message) => message,
                    Err(error) => error,
                };
                let text = wide(&message);
                if !status_hwnd.is_null() {
                    SetWindowTextW(status_hwnd, text.as_ptr());
                }
                return 0;
            }
            DefWindowProcW(hwnd, message, wparam, lparam)
        }
        WM_DESTROY => {
            PostQuitMessage(0);
            0
        }
        _ => DefWindowProcW(hwnd, message, wparam, lparam),
    }
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(std::iter::once(0)).collect()
}

fn main() -> Result<(), String> {
    // Keep the Windows target coupled to the shared cross-platform crypto core.
    let _shared_crypto_core = std::any::type_name::<tomchat_noise::NoiseSession>();

    unsafe {
        let instance = GetModuleHandleW(null());
        if instance.is_null() {
            return Err("Could not obtain the Windows application instance.".into());
        }

        let window_class = WNDCLASSW {
            style: 0,
            lpfnWndProc: Some(window_proc),
            cbClsExtra: 0,
            cbWndExtra: 0,
            hInstance: instance,
            hIcon: std::ptr::null_mut(),
            hCursor: LoadCursorW(std::ptr::null_mut(), IDC_ARROW),
            hbrBackground: std::ptr::null_mut(),
            lpszMenuName: null(),
            lpszClassName: WINDOW_CLASS.as_ptr(),
        };

        if RegisterClassW(&window_class) == 0 {
            return Err("Could not register the TomChat window class.".into());
        }

        let title = wide("TomChat");
        let hwnd = CreateWindowExW(
            0,
            WINDOW_CLASS.as_ptr(),
            title.as_ptr(),
            WS_OVERLAPPEDWINDOW | WS_VISIBLE,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            760,
            440,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            instance,
            std::ptr::null(),
        );
        if hwnd.is_null() {
            return Err("Could not create the TomChat main window.".into());
        }

        let heading = wide("Welcome to TomChat");
        let static_class = wide("STATIC");
        CreateWindowExW(
            0,
            static_class.as_ptr(),
            heading.as_ptr(),
            WS_CHILD | WS_VISIBLE,
            32,
            28,
            680,
            36,
            hwnd,
            std::ptr::null_mut(),
            instance,
            std::ptr::null(),
        );

        let description = wide(
            "Create a new identity on this device, or load an existing protected identity. Your identity is shared across your TomChat devices only when you deliberately transfer its recovery material.",
        );
        CreateWindowExW(
            0,
            static_class.as_ptr(),
            description.as_ptr(),
            WS_CHILD | WS_VISIBLE,
            32,
            76,
            680,
            64,
            hwnd,
            std::ptr::null_mut(),
            instance,
            std::ptr::null(),
        );

        let create_label = wide("Create identity");
        let button_class = wide("BUTTON");
        CreateWindowExW(
            0,
            button_class.as_ptr(),
            create_label.as_ptr(),
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON,
            32,
            164,
            180,
            40,
            hwnd,
            CREATE_ID as _,
            instance,
            std::ptr::null(),
        );

        let load_label = wide("Load existing identity");
        CreateWindowExW(
            0,
            button_class.as_ptr(),
            load_label.as_ptr(),
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON,
            232,
            164,
            220,
            40,
            hwnd,
            LOAD_ID as _,
            instance,
            std::ptr::null(),
        );

        let initial_status = match identity_store::IdentityBundle::path() {
            Ok(path) if path.exists() => {
                "An existing protected identity was found. Load it to continue; creating a new one will not overwrite it."
            }
            Ok(_) => "No identity exists on this device yet. Create one to get started.",
            Err(_) => "Windows identity storage is unavailable. Check your user profile settings.",
        };
        let status_label = wide(initial_status);
        let status_class = wide("STATIC");
        let status = CreateWindowExW(
            0,
            status_class.as_ptr(),
            status_label.as_ptr(),
            WS_CHILD | WS_VISIBLE | WS_BORDER,
            32,
            236,
            680,
            72,
            hwnd,
            std::ptr::null_mut(),
            instance,
            std::ptr::null(),
        );
        STATUS_WINDOW.store(status as isize, Ordering::Relaxed);

        let note = wide(
            "TomChat is decentralised and peer-to-peer. No central account or message server is used.",
        );
        CreateWindowExW(
            0,
            static_class.as_ptr(),
            note.as_ptr(),
            WS_CHILD | WS_VISIBLE,
            32,
            332,
            680,
            36,
            hwnd,
            std::ptr::null_mut(),
            instance,
            std::ptr::null(),
        );

        ShowWindow(hwnd, SW_SHOW);
        let mut message: MSG = std::mem::zeroed();
        while GetMessageW(&mut message, std::ptr::null_mut(), 0, 0) > 0 {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }

    Ok(())
}
