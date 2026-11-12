#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[cfg(not(target_os = "windows"))]
compile_error!("TomChat Windows must be built for a Windows target.");

mod identity_store;

use std::ptr::null;
use std::sync::atomic::{AtomicIsize, Ordering};
use tomchat_noise::contact_card::ContactCard;
use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetMessageW, GetWindowTextLengthW,
    GetWindowTextW, LoadCursorW, PostQuitMessage, RegisterClassW, SetWindowTextW, ShowWindow,
    TranslateMessage, BS_PUSHBUTTON, CW_USEDEFAULT, ES_AUTOHSCROLL, ES_MULTILINE, ES_READONLY,
    IDC_ARROW, MSG, SW_SHOW, WM_COMMAND, WM_DESTROY, WNDCLASSW, WS_BORDER, WS_CHILD,
    WS_OVERLAPPEDWINDOW, WS_TABSTOP, WS_VISIBLE, WS_VSCROLL,
};

const WINDOW_CLASS: &[u16] = &[84, 111, 109, 67, 104, 97, 116, 87, 105, 110, 100, 111, 119, 0];
const CREATE_ID: usize = 1001;
const LOAD_ID: usize = 1002;
const VERIFY_ID: usize = 1003;
static STATUS_WINDOW: AtomicIsize = AtomicIsize::new(0);
static NAME_WINDOW: AtomicIsize = AtomicIsize::new(0);
static CARD_WINDOW: AtomicIsize = AtomicIsize::new(0);
static IMPORT_WINDOW: AtomicIsize = AtomicIsize::new(0);

unsafe extern "system" fn window_proc(
    hwnd: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        WM_COMMAND => {
            let command_id = wparam & 0xffff;
            if command_id == VERIFY_ID {
                let import_hwnd = IMPORT_WINDOW.load(Ordering::Relaxed) as HWND;
                let status_hwnd = STATUS_WINDOW.load(Ordering::Relaxed) as HWND;
                let encoded = read_control_text(import_hwnd, 4096);
                let message = match ContactCard::decode(encoded.trim()) {
                    Ok(card) if !card.is_noise_compatible() => format!(
                        "This is a valid legacy contact card for '{}', but it has no authenticated Noise key. It cannot be used to establish a trusted encrypted peer session.",
                        card.display_name
                    ),
                    Ok(card) => format!(
                        "Signature valid for '{}'. Fingerprint: {}. Compare this fingerprint with the contact through a separate trusted channel before accepting them. TomChat has not added this contact.",
                        card.display_name, card.fingerprint
                    ),
                    Err(error) => format!("Contact card verification failed: {error}"),
                };
                let text = wide(&message);
                if !status_hwnd.is_null() {
                    SetWindowTextW(status_hwnd, text.as_ptr());
                }
                return 0;
            }
            if command_id == CREATE_ID || command_id == LOAD_ID {
                let name_hwnd = NAME_WINDOW.load(Ordering::Relaxed) as HWND;
                let status_hwnd = STATUS_WINDOW.load(Ordering::Relaxed) as HWND;
                let card_hwnd = CARD_WINDOW.load(Ordering::Relaxed) as HWND;
                let display_name = read_control_text(name_hwnd, 64);
                let outcome = if display_name.trim().is_empty() {
                    Err("Enter a display name before continuing.".to_string())
                } else {
                    let identity = if command_id == CREATE_ID {
                        identity_store::IdentityBundle::create()
                    } else {
                        identity_store::IdentityBundle::load()
                    };
                    identity.and_then(|identity| {
                        let card = ContactCard::create(
                            &display_name,
                            &identity.signing,
                            *identity.noise.public_key(),
                        )
                        .map_err(|error| format!("Could not create signed contact card: {error}"))?;
                        let encoded = card
                            .encode()
                            .map_err(|error| format!("Could not encode contact card: {error}"))?;
                        Ok((identity, encoded))
                    })
                };

                let (status_message, contact_card) = match outcome {
                    Ok((_identity, encoded)) => (
                        if command_id == CREATE_ID {
                            "Identity created and protected. Your signed V2 contact card is ready."
                        } else {
                            "Existing identity loaded. Your signed V2 contact card is ready."
                        }
                        .to_string(),
                        encoded,
                    ),
                    Err(error) => (error, String::new()),
                };
                let status_text = wide(&status_message);
                let card_text = wide(&contact_card);
                if !status_hwnd.is_null() {
                    SetWindowTextW(status_hwnd, status_text.as_ptr());
                }
                if !card_hwnd.is_null() {
                    SetWindowTextW(card_hwnd, card_text.as_ptr());
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

unsafe fn read_control_text(hwnd: HWND, max_length: i32) -> String {
    if hwnd.is_null() {
        return String::new();
    }
    let length = GetWindowTextLengthW(hwnd);
    if length <= 0 || length > max_length {
        return String::new();
    }
    let mut buffer = vec![0u16; length as usize + 1];
    let copied = GetWindowTextW(hwnd, buffer.as_mut_ptr(), buffer.len() as i32);
    String::from_utf16_lossy(&buffer[..copied.max(0) as usize])
}

fn main() -> Result<(), String> {
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
            820,
            820,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            instance,
            std::ptr::null(),
        );
        if hwnd.is_null() {
            return Err("Could not create the TomChat main window.".into());
        }

        let static_class = wide("STATIC");
        let heading = wide("Welcome to TomChat");
        CreateWindowExW(0, static_class.as_ptr(), heading.as_ptr(), WS_CHILD | WS_VISIBLE,
            28, 22, 720, 32, hwnd, std::ptr::null_mut(), instance, std::ptr::null());

        let description = wide("Create a new identity on this device, or load your existing protected identity.");
        CreateWindowExW(0, static_class.as_ptr(), description.as_ptr(), WS_CHILD | WS_VISIBLE,
            28, 62, 720, 28, hwnd, std::ptr::null_mut(), instance, std::ptr::null());

        let name_label = wide("Display name");
        CreateWindowExW(0, static_class.as_ptr(), name_label.as_ptr(), WS_CHILD | WS_VISIBLE,
            28, 108, 150, 24, hwnd, std::ptr::null_mut(), instance, std::ptr::null());

        let edit_class = wide("EDIT");
        let name_value = wide("");
        let name_control = CreateWindowExW(
            0, edit_class.as_ptr(), name_value.as_ptr(),
            WS_CHILD | WS_VISIBLE | WS_BORDER | WS_TABSTOP | ES_AUTOHSCROLL,
            28, 134, 360, 30, hwnd, std::ptr::null_mut(), instance, std::ptr::null(),
        );
        NAME_WINDOW.store(name_control as isize, Ordering::Relaxed);

        let button_class = wide("BUTTON");
        let create_label = wide("Create identity");
        CreateWindowExW(0, button_class.as_ptr(), create_label.as_ptr(),
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON,
            28, 182, 170, 38, hwnd, CREATE_ID as _, instance, std::ptr::null());

        let load_label = wide("Load existing identity");
        CreateWindowExW(0, button_class.as_ptr(), load_label.as_ptr(),
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON,
            214, 182, 210, 38, hwnd, LOAD_ID as _, instance, std::ptr::null());

        let initial_status = match identity_store::IdentityBundle::path() {
            Ok(path) if path.exists() => "An existing protected identity was found. Load it to continue.",
            Ok(_) => "No identity exists on this device yet. Create one to get started.",
            Err(_) => "Windows identity storage is unavailable. Check your user profile settings.",
        };
        let status_label = wide(initial_status);
        let status_class = wide("STATIC");
        let status = CreateWindowExW(0, status_class.as_ptr(), status_label.as_ptr(),
            WS_CHILD | WS_VISIBLE | WS_BORDER,
            28, 238, 720, 52, hwnd, std::ptr::null_mut(), instance, std::ptr::null());
        STATUS_WINDOW.store(status as isize, Ordering::Relaxed);

        let card_label = wide("Your signed V2 contact card (select and copy to share)");
        CreateWindowExW(0, static_class.as_ptr(), card_label.as_ptr(), WS_CHILD | WS_VISIBLE,
            28, 312, 720, 24, hwnd, std::ptr::null_mut(), instance, std::ptr::null());

        let empty_card = wide("");
        let card = CreateWindowExW(
            0, edit_class.as_ptr(), empty_card.as_ptr(),
            WS_CHILD | WS_VISIBLE | WS_BORDER | WS_TABSTOP | ES_MULTILINE | ES_READONLY | WS_VSCROLL,
            28, 340, 720, 150, hwnd, std::ptr::null_mut(), instance, std::ptr::null(),
        );
        CARD_WINDOW.store(card as isize, Ordering::Relaxed);

        let import_label = wide("Verify a contact card (paste the full card here)");
        CreateWindowExW(0, static_class.as_ptr(), import_label.as_ptr(), WS_CHILD | WS_VISIBLE,
            28, 520, 720, 24, hwnd, std::ptr::null_mut(), instance, std::ptr::null());

        let empty_import = wide("");
        let import_control = CreateWindowExW(
            0, edit_class.as_ptr(), empty_import.as_ptr(),
            WS_CHILD | WS_VISIBLE | WS_BORDER | WS_TABSTOP | ES_MULTILINE | WS_VSCROLL,
            28, 548, 720, 120, hwnd, std::ptr::null_mut(), instance, std::ptr::null(),
        );
        IMPORT_WINDOW.store(import_control as isize, Ordering::Relaxed);

        let verify_label = wide("Verify contact card");
        CreateWindowExW(0, button_class.as_ptr(), verify_label.as_ptr(),
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON,
            28, 684, 190, 38, hwnd, VERIFY_ID as _, instance, std::ptr::null());

        let note = wide("TomChat is decentralised and peer-to-peer. No central account or message server is used.");
        CreateWindowExW(0, static_class.as_ptr(), note.as_ptr(), WS_CHILD | WS_VISIBLE,
            28, 744, 720, 30, hwnd, std::ptr::null_mut(), instance, std::ptr::null());

        ShowWindow(hwnd, SW_SHOW);
        let mut message: MSG = std::mem::zeroed();
        while GetMessageW(&mut message, std::ptr::null_mut(), 0, 0) > 0 {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }

    Ok(())
}


#[cfg(test)]
mod interoperability_tests {
    use super::ContactCard;
    use tomchat_noise::contact_card::SigningIdentity;
    use tomchat_noise::identity::NoiseIdentity;

    #[test]
    fn verifies_the_android_shared_v2_contact_card_fixture() {
        let fixture =
            include_str!("../../app/src/test/resources/contact-card-v2.txt").trim();
        let card = ContactCard::decode(fixture).expect("Android V2 fixture must verify");
        assert!(card.is_noise_compatible());
        assert_eq!(
            card.display_name,
            "TomChat shared interoperability fixture"
        );
    }

    #[test]
    fn windows_generated_contact_card_round_trips_with_noise_binding() {
        let noise = NoiseIdentity::generate().expect("Noise identity generation");
        let signing = SigningIdentity::generate().expect("signing identity generation");
        let card = ContactCard::create(
            "TomChat Windows test",
            &signing,
            *noise.public_key(),
        )
        .expect("V2 card creation");
        let encoded = card.encode().expect("V2 card encoding");
        let decoded = ContactCard::decode(&encoded).expect("V2 card verification");
        assert_eq!(decoded, card);
        assert!(decoded.is_noise_compatible());
    }

    #[test]
    fn rejects_contact_card_with_changed_noise_key() {
        let noise = NoiseIdentity::generate().expect("Noise identity generation");
        let signing = SigningIdentity::generate().expect("signing identity generation");
        let card = ContactCard::create("Tamper test", &signing, *noise.public_key())
            .expect("V2 card creation");
        let encoded = card.encode().expect("V2 card encoding");
        let mut fields: Vec<String> = encoded.split('.').map(str::to_owned).collect();
        fields[4] = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        assert!(ContactCard::decode(&fields.join(".")).is_err());
    }
}
