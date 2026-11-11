# TomChat age-aware account setup

## Product behaviour

TomChat remains one Android application. The account setup flow asks for a display name and date of birth, then assigns an experience band locally:

| Calculated age | Assigned experience |
| --- | --- |
| Under 5 | Guardian-led setup required; independent setup is blocked |
| 5–8 | TomChat Kids (5–8) |
| 9–12 | TomChat Kids (9–12) |
| 13–17 | TomChat Teen |
| 18+ | TomChat Adult |

Age is calculated using the user's local calendar date and completed birthdays, not by subtracting birth years alone. The boundaries are centralised in `AgeBandPolicy` and covered by unit tests.

## Important current limitation

The current screen is a development prototype. It accepts a self-declared date of birth and previews the assigned age band. It does **not** verify a person's age, verify a guardian, obtain legally meaningful parental consent, or securely persist an account profile. It must not be treated as an age-assurance system, and no adult-only or child-facing production features should be unlocked solely on this self-declaration.

The production flow needs a reviewed age-assurance design and a separate guardian-consent ceremony for children where required. The verification method must be privacy-preserving, minimise disclosure, and must not silently introduce a TomChat-operated or third-party application centralised service. If a proposed verification method needs an online service, it must be explicitly assessed against the strict fully decentralised architecture requirement before adoption.

## Data minimisation

- The current prototype retains the entered date of birth only in transient UI state while the setup screen is open.
- The app passes only the calculated age band into the main UI.
- The finished account should retain a minimal age-band/assurance result and policy version, not the full date of birth, unless a documented legal requirement makes retention necessary.
- Age band, age assurance and guardian consent are distinct states. A band assignment is not proof of age or consent.
- Account restrictions must be enforced in domain/use-case layers as well as the UI; hiding a screen is not an access-control boundary.
- Age-band transitions must be recalculated safely as time passes and require re-evaluation of guardian settings before unlocking additional capabilities.

## Safety requirements before release

- No public child profile discovery or unrestricted public groups.
- Guardian-approved contact invitations for younger accounts, with clear child-visible contact controls.
- Block/report controls and safe handling of unwanted contact.
- No advertising, behavioural profiling, or unnecessary collection of children's data.
- Guardian pairing must be authenticated and revocable, and must not grant a guardian silent access to message contents.
- Review applicable age-assurance, child privacy, consent and online-safety requirements for each launch jurisdiction.
- Threat-model account recovery, date-of-birth changes, device replacement, guardian-device loss, and attempts to downgrade safety restrictions.

## fully decentralised architecture boundary

Age-band calculation can be local. That does not make age verification or guardian consent trustworthy by itself. Any eventual proof should be verified locally where technically possible (for example, using a verifiable credential issued by a suitable independent process), with no TomChat account centralised service, directory, relay, or hosted guardian service. Whether a credential issuer is compatible with the product's strict fully decentralised promise requires a separate product decision and legal/privacy review.

## Central capability policy prototype

A domain-level policy now separates three inputs:

- calculated age band;
- age-assurance status (`NOT_VERIFIED` / `VERIFIED`);
- guardian-consent status (`NOT_REQUIRED` / `PENDING` / `APPROVED` / `DENIED`).

`AccountSafetyPolicy` returns explicit capability decisions. In the current default account state, all communication capabilities remain locked because age assurance has not been completed; minor accounts also require guardian approval. The prototype capability matrix is deliberately conservative:

- Ages 5–12: contact-card exchange and messaging to approved contacts only.
- Ages 13–17: approved-contact messaging, calls, file sharing and private groups; no public discovery or public social posting.
- Adults: standard capabilities only after verified age and a consistent `NOT_REQUIRED` guardian state.
- Under 5: independent account setup and capabilities remain blocked.

These are initial product rules for review, not a claim of legal compliance. Before release, the matrix needs a safeguarding review and enforcement at every use-case boundary. UI visibility alone is not a security control. Tests cover unverified accounts, pending/denied guardian approval, age-specific capabilities and inconsistent adult guardian state.

## Encrypted local account profile (prototype)

The Android first-run flow now saves a minimal profile in the app's private files directory. The profile contains only the display name, assigned age band, age-assurance status and guardian-consent status. The raw date of birth is not included in the profile model or serialized payload.

The profile is encrypted with AES-256-GCM. Its random IV and ciphertext are stored in an atomic file, and the AES key is generated and held by Android Keystore. The format version is authenticated as GCM additional authenticated data. Reads fail closed if the profile cannot be decrypted or the original Keystore key is missing; the app does not silently create a replacement key or overwrite an unreadable profile.

The profile codec has JVM unit tests for round-trip serialization, trailing-data rejection and invalid age/guardian combinations. The Keystore and atomic-file integration still needs Android instrumentation/device testing, including app restart, app-data restore behaviour, key invalidation, interrupted writes and device migration. This is local encrypted storage, not yet a complete identity, backup or recovery system.

## Adult-only local testing override (debug builds only)

For controlled real-device testing, the Android debug variant has a narrowly scoped test override:

- It applies only when the locally assigned age band is `ADULT_18_PLUS` and guardian state is `NOT_REQUIRED`.
- It permits signed contact-card exchange, approved-contact messaging and one-to-one voice calling while age assurance is still `NOT_VERIFIED`.
- It does not enable public profile discovery, public posting, groups, file sharing or other capabilities.
- It never applies to child or teen age bands; those accounts remain blocked until the normal age-assurance and guardian-consent checks pass.
- The UI displays a persistent warning in the Chats and Contacts screens.
- The override is gated by Android's generated `BuildConfig.DEBUG`; release builds compile with it disabled and continue to enforce the normal policy.

This is a development convenience, not an age-verification mechanism or a production policy. Only use the debug APK with consenting adult testers. Do not distribute a debug build as a public release. Before a production launch, remove or separately review this testing facility and complete the age-assurance and child-safeguarding work.
