# TomChat visual design system

## Reference repositories

TomChat uses two first-party TomOS repositories as its design references:

1. [Tom OS Hyprland 1](https://github.com/Spruhantom/tom-os-hyperland1) is the primary visual and brand source: neon-punk palette, dark surfaces, TomOS logo, Neon Eclipse wallpaper artwork, magenta/cyan accents, and the overall TomOS identity.
2. [TomOS Android SIP](https://github.com/Spruhantom/Tomos-androidsip) is the Android interaction and communication-app reference: Kotlin + Jetpack Compose, call and contact workflows, dial-pad patterns, active-call controls, notification/service handling, and the existing TomOS mobile brand treatment.

TomChat should feel like the next member of the TomOS app family, not a clone of either app. Use the Hyperland repository for visual identity and SIP for mobile interaction patterns.

## Canonical colour tokens

Use the palette defined in the TomOS GTK theme as the canonical starting point. Android Compose tokens should be named and centralized rather than scattered as inline colour literals.

| Token | Hex | Usage |
|---|---|---|
| Void | `#0A0A12` | Main app background |
| Panel | `#14141F` | Cards, navigation and sheets |
| Raised | `#1D1D2B` | Elevated surfaces and input fields |
| Text | `#E8E8F0` | Primary text |
| Muted | `#8A8AA0` | Secondary text and metadata |
| Neon Pink | `#FF2D96` | Main brand accent and selected states |
| Neon Purple | `#C74CFF` | Secondary accent and gradients |
| Neon Cyan | `#00F0FF` | Focus, outlines, connection indicators |
| Neon Green | `#00FF9C` | Connected / success state |
| Neon Yellow | `#FFE14D` | Warnings |
| Neon Red | `#FF2D78` | Errors / destructive state |

The existing SIP app uses a closely related dark/pink/cyan palette. Align it to these canonical TomOS tokens while preserving its useful contrast and accessible text sizing.

## Visual language

- Dark-first interface with restrained neon highlights; avoid making every surface glow.
- Rounded cards and controls, thin magenta borders, occasional cyan focus rings, and selective pink-to-purple gradients for primary actions.
- Use the official TomOS logo from the Hyperland repository's `assets/logo-tomos.png` or the SIP app's existing `tomos_logo.png` as the source asset; verify visual variants and licensing before packaging.
- Use TomOS Neon Eclipse artwork as optional onboarding or profile/background imagery, not as a busy background behind message text.
- Typography should remain highly legible on phone screens. Use a suitable bundled/system sans for body text and a restrained monospace face only for technical IDs, diagnostics, or connection details.
- Provide reduced-motion support and never use flashing/pulsing effects for message or call status.

## Android navigation and interaction

Use the SIP client's Jetpack Compose implementation as a reference for Android-native layouts and state handling. Reuse design ideas, not SIP-specific business logic or SDK dependencies.

TomChat's core navigation should prioritize:
- Chats / inbox
- Contacts and nearby devices
- Calls and call history
- Settings and identity

The later TomSocial experience should be a separate destination within the same app, introduced after core messaging is stable. Keep private chats visually distinct from public posts and community content.

Communication screens can adapt familiar SIP patterns:
- Contact cards and one-tap actions.
- A dedicated full-screen active-call view with clear mute, speaker, Bluetooth audio-route, keypad, and end-call controls.
- Clear incoming/outgoing/connecting/connected call states.
- Persistent but non-intrusive call notification and foreground-service behavior where Android requires it.

Messaging should have its own purpose-built layout: readable conversation bubbles, clear delivery/encryption status, attachment and voice-message controls, and a composer that remains usable with Android accessibility and keyboard features.

## Accessibility and responsive behaviour

- Meet WCAG-style contrast targets for text and essential controls; neon accents are not a substitute for readable contrast.
- Do not encode connection, encryption, or call state by colour alone; pair icons and labels with colour.
- Respect font scaling, display cutouts, gesture navigation, landscape, tablets and foldables.
- Support light theme only if it can be made coherent and readable; dark TomOS neon remains the default.
- Animations should be short, purposeful, and respect Android's system reduced-motion preference.

## Implementation boundaries

- Implement the design in native Jetpack Compose Material 3 components and centralized theme tokens.
- Do not port GTK CSS, Quickshell/QML, Hyprland configuration, or desktop-only effects into Android.
- Do not import the SIP app's Linphone SDK, SIP account model, or SIP networking into TomChat. Its UI and lifecycle patterns are references only.
- Keep this visual system independent of the zero-server transport and cryptographic layers. Branding must not create telemetry, remote asset, font, or theme-service dependencies.
