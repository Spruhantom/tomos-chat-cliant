#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[cfg(not(target_os = "windows"))]
compile_error!("TomChat Windows must be built for a Windows target.");

use std::ptr::null;
use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetMessageW, LoadCursorW, PostQuitMessage,
    RegisterClassW, ShowWindow, TranslateMessage, CW_USEDEFAULT, IDC_ARROW, MSG, SW_SHOW,
    WM_DESTROY, WNDCLASSW, WS_CHILD, WS_OVERLAPPEDWINDOW, WS_VISIBLE,
};

const WINDOW_CLASS: &[u16] = &[
    84, 111, 109, 67, 104, 97, 116, 87, 105, 110, 100, 111, 119, 0,
];

unsafe extern "system" fn window_proc(
    hwnd: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
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
    // The shared Noise crate is deliberately part of this Windows target.
    // Identity persistence and peer-session wiring are added only after the
    // Windows credential-store boundary is implemented and tested.
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
            1100,
            760,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            instance,
            std::ptr::null(),
        );
        if hwnd.is_null() {
            return Err("Could not create the TomChat main window.".into());
        }

        let label_text = wide(
            "TomChat for Windows\n\nNative Rust / Win32 foundation\n\n             Decentralised peer-to-peer messaging — no application server.\n\n             Windows secure identity storage and shared Noise transport integration are next.",
        );
        let label_class = wide("STATIC");
        CreateWindowExW(
            0,
            label_class.as_ptr(),
            label_text.as_ptr(),
            WS_CHILD | WS_VISIBLE,
            36,
            36,
            1000,
            180,
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
