# HeadLink privacy policy

Effective 27 October 2026. Applies to the HeadLink Android app
(`com.jw6ventures.headlink`) published by JW6 Ventures.

## The short version

HeadLink has no account, no ads, no analytics, no crash reporting, and no server of its
own. It keeps what it needs on your phone. The only time it sends anything over the
internet is when you turn on live traffic, and then it sends only what is described under
[Live traffic](#live-traffic-here).

## What stays on your phone

These are stored in the app's private storage. Android backup and device transfer are
turned off for all of it, so none of it is copied to a cloud backup or a new phone.

- **Settings** you choose in the app.
- **The phone's source identity** (a certificate and private key) and **the certificates
  of head units you have trusted**. These are how the car and the phone recognise each
  other.
- **Map, routing and address files** you import. HeadLink never downloads them.
- **Saved places and recent destinations.**
- **Diagnostic reports** of each connection attempt. A report records how the connection
  went (timings, counters, what the head unit offered, errors). It does not contain your
  location, destinations, routes, callers, phone numbers, or notification content. Device
  serial numbers are redacted and Wi-Fi network names are replaced by a salted hash.
- **Your HERE API key**, if you enter one.

Uninstalling HeadLink deletes all of it.

## What HeadLink reads, and only when you turn it on

Each of these is optional and off until you enable it. Android asks you for the matching
permission at that point, and you can withdraw it at any time in Android's settings.

- **Location.** Used only when the car does not report its own position, and to route a
  destination before you plug in. The position stays on the phone and is never put in a
  report.
- **Notification access.** Used to show what is playing on the head unit, to show
  notifications on the dashboard if you turn that on, and for call buttons when the call
  controls grant is not set up. Notifications are held in memory for the drive only and
  are never saved or reported. Android does not offer a narrower permission, so while
  access is granted the app is technically able to see all notifications; it reads them
  only for the features you have turned on.
- **Calls.** With Call controls on, HeadLink sees the current call so it can answer,
  decline or hang up from the dashboard. Callers, numbers and keypad digits are never
  saved or reported.
- **Phone state.** Used only for the signal-strength bars on the dashboard.
- **Nearby devices (Bluetooth).** Used to find and link your car for wireless projection.
  Device names and addresses are not put in reports.
- **Weather.** Read from the Breezy Weather app on your phone, if installed and allowed.
  No network is used for it.

## Live traffic (HERE)

Live traffic is off by default and needs your own HERE API key. While it is on and a route
is being followed, HeadLink sends HERE, every 10 minutes (or the interval you set):

- the rectangle around the next 30 km of your route, which shows HERE roughly where you
  are, and
- your API key.

HERE also sees your IP address and the time of each request. HeadLink does not send your
destination or the route itself. HeadLink keeps nothing it receives beyond the next
response. HERE's handling of this data is governed by HERE's own privacy policy:
<https://legal.here.com/us-en/privacy>.

With live traffic off, HeadLink makes no internet connection.

## Other apps HeadLink works with

- **Text-to-speech.** Spoken turn prompts and alerts are produced by whichever speech
  engine is the system default on your phone. If that engine uses the network, its
  publisher's policy applies.
- **Sharing.** If you export a diagnostic report, or share a destination into HeadLink,
  the app you share with or from handles that data under its own policy.
- **The car.** Like any Android Auto source, HeadLink sends the car the video it draws,
  media and navigation information, and touch responses, over USB or the car's own
  Wi-Fi. What the car does with it is up to the car's maker.

## Children

HeadLink is not directed at children and collects no personal information from anyone.

## Changes

Changes to this policy are published in this file in the project repository, with the
effective date updated. The history of every change is in the repository's commit log.

## Contact

Questions about this policy: open an issue at
<https://github.com/jw6ventures/headlink/issues>.
