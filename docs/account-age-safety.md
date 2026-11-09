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

The production flow needs a reviewed age-assurance design and a separate guardian-consent ceremony for children where required. The verification method must be privacy-preserving, minimise disclosure, and must not silently introduce a TomChat-operated or third-party application server. If a proposed verification method needs an online service, it must be explicitly assessed against the strict no-application-server requirement before adoption.

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

## Serverless architecture boundary

Age-band calculation can be local. That does not make age verification or guardian consent trustworthy by itself. Any eventual proof should be verified locally where technically possible (for example, using a verifiable credential issued by a suitable independent process), with no TomChat account server, directory, relay, or hosted guardian service. Whether a credential issuer is compatible with the product's strict serverless promise requires a separate product decision and legal/privacy review.
