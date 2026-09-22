//! Now-playing on its way to the head unit, and head-unit transport buttons on their way
//! back (spec §8.4, P5.1). [`NowPlayingLink`] is shared by the app, which publishes what
//! is currently playing, and the session thread, which sends it on the `media_info`
//! channel as the rate limit allows.
//!
//! **This source does not own the playback it describes.** The app reports another
//! application's session when the operator has granted it that access, and otherwise
//! reports nothing; the session publishes whatever it is given and invents no state of
//! its own. A head unit that is told nothing is told nothing, rather than being shown a
//! plausible-looking placeholder.
//!
//! Strings are bounded here rather than at the app boundary, because everything on this
//! path ends up on someone else's screen: [`TEXT_LIMIT`] characters per field, control
//! characters removed. Album art is not carried at all; see
//! [`crate::projection::ProjectionReport::media_album_art`].
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};

/// Characters kept per now-playing string. Four of these plus the message overhead stay
/// far inside one 16 KiB fragment, so a metadata message is never fragmented.
pub const TEXT_LIMIT: usize = 192;

/// Android keycodes this source treats as transport controls. Everything else stays an
/// ordinary [`crate::projection::AppEvent::Key`].
mod keycode {
    pub const MEDIA_PLAY_PAUSE: u32 = 85;
    pub const MEDIA_STOP: u32 = 86;
    pub const MEDIA_NEXT: u32 = 87;
    pub const MEDIA_PREVIOUS: u32 = 88;
    pub const MEDIA_REWIND: u32 = 89;
    pub const MEDIA_FAST_FORWARD: u32 = 90;
    pub const MEDIA_PLAY: u32 = 126;
    pub const MEDIA_PAUSE: u32 = 127;
}

/// A head-unit transport button, named rather than left as a bare keycode so the app
/// never has to re-derive the mapping.
///
/// Ford SYNC 4.0 binds 85, 87, 88, 126 and 127 (report 617af5ce); it advertises no
/// rewind or fast-forward button, so [`TransportAction::Rewind`] and
/// [`TransportAction::FastForward`] are carried for head units that do.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportAction {
    PlayPause,
    Play,
    Pause,
    Stop,
    Next,
    Previous,
    /// Conventionally a fixed step backwards; how far is the playing application's
    /// choice unless the app can seek the session itself.
    Rewind,
    FastForward,
}

impl TransportAction {
    /// The action a head-unit keycode selects, or `None` if it is not a transport button.
    pub fn from_keycode(keycode: u32) -> Option<Self> {
        Some(match keycode {
            keycode::MEDIA_PLAY_PAUSE => TransportAction::PlayPause,
            keycode::MEDIA_PLAY => TransportAction::Play,
            keycode::MEDIA_PAUSE => TransportAction::Pause,
            keycode::MEDIA_STOP => TransportAction::Stop,
            keycode::MEDIA_NEXT => TransportAction::Next,
            keycode::MEDIA_PREVIOUS => TransportAction::Previous,
            keycode::MEDIA_REWIND => TransportAction::Rewind,
            keycode::MEDIA_FAST_FORWARD => TransportAction::FastForward,
            _ => return None,
        })
    }

    pub fn name(self) -> &'static str {
        match self {
            TransportAction::PlayPause => "play-pause",
            TransportAction::Play => "play",
            TransportAction::Pause => "pause",
            TransportAction::Stop => "stop",
            TransportAction::Next => "next",
            TransportAction::Previous => "previous",
            TransportAction::Rewind => "rewind",
            TransportAction::FastForward => "fast-forward",
        }
    }

    pub fn from_name(name: &str) -> Option<Self> {
        [
            TransportAction::PlayPause,
            TransportAction::Play,
            TransportAction::Pause,
            TransportAction::Stop,
            TransportAction::Next,
            TransportAction::Previous,
            TransportAction::Rewind,
            TransportAction::FastForward,
        ]
        .into_iter()
        .find(|a| a.name() == name)
    }
}

/// Playback state, mirroring the vendored `PlaybackState` enum. Kept as its own type so
/// the app boundary names states rather than passing the wire integer through.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PlaybackState {
    /// Nothing is known to be playing. `PLAYBACK_STATE_UNKNOWN` on the wire; the head
    /// unit maps it onto `STATE_NONE`.
    #[default]
    Unknown,
    Stopped,
    Playing,
    Paused,
    Error,
}

impl PlaybackState {
    pub fn wire(self) -> i32 {
        match self {
            PlaybackState::Unknown => 0,
            PlaybackState::Stopped => 1,
            PlaybackState::Playing => 2,
            PlaybackState::Paused => 3,
            PlaybackState::Error => 4,
        }
    }

    pub fn from_wire(value: i32) -> Option<Self> {
        Some(match value {
            0 => PlaybackState::Unknown,
            1 => PlaybackState::Stopped,
            2 => PlaybackState::Playing,
            3 => PlaybackState::Paused,
            4 => PlaybackState::Error,
            _ => return None,
        })
    }

    pub fn name(self) -> &'static str {
        match self {
            PlaybackState::Unknown => "unknown",
            PlaybackState::Stopped => "stopped",
            PlaybackState::Playing => "playing",
            PlaybackState::Paused => "paused",
            PlaybackState::Error => "error",
        }
    }

    pub fn from_name(name: &str) -> Option<Self> {
        [
            PlaybackState::Unknown,
            PlaybackState::Stopped,
            PlaybackState::Playing,
            PlaybackState::Paused,
            PlaybackState::Error,
        ]
        .into_iter()
        .find(|s| s.name() == name)
    }
}

/// What the head unit is told is playing. Every string is already bounded and cleaned by
/// [`NowPlaying::new`]; nothing else on the path repeats that work.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct NowPlaying {
    pub state: PlaybackState,
    /// Elapsed seconds in the current track. The wire field is seconds, so a source that
    /// knows the position more precisely still reports whole seconds.
    pub position_seconds: u32,
    /// Which application the audio belongs to, as the app resolved it. Empty when the
    /// app has no way to know.
    pub source_app: String,
    pub title: String,
    pub artist: String,
    pub album: String,
}

impl NowPlaying {
    pub fn new(
        state: PlaybackState,
        position_seconds: u32,
        source_app: &str,
        title: &str,
        artist: &str,
        album: &str,
    ) -> Self {
        Self {
            state,
            position_seconds,
            source_app: clean(source_app),
            title: clean(title),
            artist: clean(artist),
            album: clean(album),
        }
    }

    /// Whether anything identifies the track. A status message is still worth sending
    /// without this (a state and a position are useful on their own), but a metadata
    /// message carrying three empty strings is not.
    pub fn has_track(&self) -> bool {
        !(self.title.is_empty() && self.artist.is_empty() && self.album.is_empty())
    }

    /// What a metadata message is sent for: the track changed.
    pub(crate) fn track(&self) -> (&str, &str, &str) {
        (&self.title, &self.artist, &self.album)
    }

    /// What a status message is sent for: the state, position, or owning app changed.
    pub(crate) fn status(&self) -> (PlaybackState, u32, &str) {
        (self.state, self.position_seconds, &self.source_app)
    }

    /// [`NowPlaying::track`] as owned strings, for the copy kept of what was sent.
    pub(crate) fn track_owned(&self) -> (String, String, String) {
        (self.title.clone(), self.artist.clone(), self.album.clone())
    }

    /// [`NowPlaying::status`] as owned strings, for the copy kept of what was sent.
    pub(crate) fn status_owned(&self) -> (PlaybackState, u32, String) {
        (self.state, self.position_seconds, self.source_app.clone())
    }
}

/// Bounded, control-free text for a string that ends up on the head unit's screen.
pub(crate) fn clean(text: &str) -> String {
    text.chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .take(TEXT_LIMIT)
        .collect()
}

/// The app → session hand-off for now-playing. Shared through an `Arc`; the app
/// publishes and the session takes snapshots, so neither blocks the other for longer
/// than a field copy.
#[derive(Default)]
pub struct NowPlayingLink {
    current: Mutex<Option<NowPlaying>>,
    waker: Mutex<Option<Arc<dyn Fn() + Send + Sync>>>,
}

impl std::fmt::Debug for NowPlayingLink {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("NowPlayingLink")
            .field("current", &self.snapshot())
            .finish_non_exhaustive()
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

impl NowPlayingLink {
    /// Wakes the session thread when the app publishes, so a track change does not wait
    /// for the next timer. Set once by the transport owner.
    pub fn set_waker(&self, waker: Arc<dyn Fn() + Send + Sync>) {
        *lock(&self.waker) = Some(waker);
    }

    /// App thread: replace what the head unit is told. Returns whether this differs from
    /// what was already published, so a caller polling an unchanged player can tell that
    /// it changed nothing.
    pub fn publish(&self, now_playing: NowPlaying) -> bool {
        let changed = {
            let mut current = lock(&self.current);
            let changed = current.as_ref() != Some(&now_playing);
            *current = Some(now_playing);
            changed
        };
        if changed {
            let waker = lock(&self.waker).clone();
            if let Some(waker) = waker {
                waker();
            }
        }
        changed
    }

    /// App thread: the operator withdrew the app's access to what is playing, or nothing
    /// is playing any more. The head unit is told the playback stopped rather than being
    /// left showing a stale track.
    pub fn clear(&self) -> bool {
        self.publish(NowPlaying {
            state: PlaybackState::Stopped,
            ..NowPlaying::default()
        })
    }

    /// Session thread: what the app last published, or `None` if it has published
    /// nothing at all. `None` and a published empty [`NowPlaying`] are different: the
    /// first has never been told anything, the second has been told there is nothing.
    pub fn snapshot(&self) -> Option<NowPlaying> {
        lock(&self.current).clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transport_keycodes_map_both_ways() {
        // Every keycode Ford SYNC 4.0 binds for media (report 617af5ce).
        for (keycode, action) in [
            (85, TransportAction::PlayPause),
            (87, TransportAction::Next),
            (88, TransportAction::Previous),
            (126, TransportAction::Play),
            (127, TransportAction::Pause),
        ] {
            assert_eq!(TransportAction::from_keycode(keycode), Some(action));
            assert_eq!(TransportAction::from_name(action.name()), Some(action));
        }
        // Ford also binds these, and they are not transport controls: KEYCODE_SEARCH,
        // KEYCODE_MEDIA (65537) and KEYCODE_NAVIGATION (65538) stay ordinary keys.
        for keycode in [84, 65537, 65538, 0] {
            assert_eq!(TransportAction::from_keycode(keycode), None);
        }
    }

    #[test]
    fn playback_states_round_trip_through_the_wire_values() {
        for state in [
            PlaybackState::Unknown,
            PlaybackState::Stopped,
            PlaybackState::Playing,
            PlaybackState::Paused,
            PlaybackState::Error,
        ] {
            assert_eq!(PlaybackState::from_wire(state.wire()), Some(state));
            assert_eq!(PlaybackState::from_name(state.name()), Some(state));
        }
        assert_eq!(PlaybackState::from_wire(5), None);
    }

    #[test]
    fn published_strings_are_bounded_and_control_free() {
        let long = "a".repeat(TEXT_LIMIT + 50);
        let playing = NowPlaying::new(
            PlaybackState::Playing,
            12,
            "com.example\u{7}player",
            &long,
            "Artist\nName",
            "",
        );
        assert_eq!(playing.title.chars().count(), TEXT_LIMIT);
        assert_eq!(playing.artist, "Artist Name");
        assert_eq!(playing.source_app, "com.example player");
        assert!(playing.has_track());
        assert!(!NowPlaying::default().has_track());
    }

    #[test]
    fn publishing_reports_only_real_changes_and_clearing_stops_playback() {
        let link = NowPlayingLink::default();
        assert_eq!(link.snapshot(), None);
        let playing = NowPlaying::new(PlaybackState::Playing, 1, "app", "T", "A", "B");
        assert!(link.publish(playing.clone()));
        assert!(!link.publish(playing));
        assert_eq!(link.snapshot().unwrap().state, PlaybackState::Playing);
        assert!(link.clear());
        let cleared = link.snapshot().unwrap();
        assert_eq!(cleared.state, PlaybackState::Stopped);
        assert!(!cleared.has_track());
    }

    #[test]
    fn publishing_wakes_the_session_thread_only_on_a_change() {
        let woken = Arc::new(Mutex::new(0u32));
        let link = NowPlayingLink::default();
        let counter = woken.clone();
        link.set_waker(Arc::new(move || *lock(&counter) += 1));
        let playing = NowPlaying::new(PlaybackState::Playing, 1, "app", "T", "", "");
        link.publish(playing.clone());
        link.publish(playing);
        assert_eq!(*lock(&woken), 1);
    }
}
