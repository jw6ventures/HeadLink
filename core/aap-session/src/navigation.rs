//! Turn-by-turn on its way to the head unit's own instrument cluster (P6.2). This module
//! produces the *content* — which maneuver is next, how far away it is, and how it should
//! be written; [`crate::projection`] puts it on the `navigation` channel.
//!
//! **Nothing here is a route.** No route exists yet — P6.3 is the routing engine — so the
//! sequence is scripted, which is the same move P5.3 made when it sent a generated tone
//! rather than capturing another application's playback, and for the same reason: it makes
//! the gate checkable without first solving the harder problem behind it. P6.5 replaces
//! [`Script`] with a guidance loop over a real route and changes nothing on the wire.
//!
//! It is scripted **in the source rather than in the app**, which is where P5.3 put its
//! tone. The difference is ownership: only the app can own audio, so the app had to
//! produce it, while a maneuver here belongs to a route nothing on the phone has yet. A
//! script the session owns is also one a host test and a vehicle run can play identically
//! from the same configuration line.
//!
//! **Everything is derived from one number.** At any instant the script knows only how far
//! along the route it is; the distance to the next maneuver, the time to it, the distance
//! to the destination and the time to that are all arithmetic on that one position. This
//! is the rule P6.1's track player established for position, speed and bearing — a source
//! reading four independent tables could put a maneuver 300 m away and 40 s away in the
//! same message and have neither be wrong on its own.
//!
//! **What is deliberately not here.** No lanes: the schema carries `NavigationLane` and
//! the P6 plan §0 puts lane guidance out of scope, and filling it from a script would make
//! the gate evidence claim a capability this project has no way to produce for real. No
//! turn images: P6 plan §2 starts semantic rather than pictorial, and the only wire path
//! that ever carried image bytes is deprecated. No estimated time of arrival string: the
//! field is a `string` of unrecorded format and this source does not know the vehicle's
//! local clock, so it sends the seconds remaining and leaves the text absent rather than
//! putting an invented clock time on a driver's cluster.
//!
//! **P6.5 adds the real thing beside the script rather than in place of it.** With
//! [`Source::Route`], the maneuvers come from the app's guidance loop over a route BRouter
//! computed, published through a [`GuidanceLink`] exactly as now-playing is published
//! through its own link; the wire path is unchanged. The script stays, because it is the
//! only source that can put a whole maneuver sequence on a cluster without a drive — which
//! is what G9 was passed with, and what the lab's `navigation` script still checks.
use aap_proto::{enums::maneuver_type, messages::DistanceDisplayUnit};
use std::{
    fmt,
    sync::{Arc, Mutex, MutexGuard, PoisonError},
    time::Duration,
};

/// Metres in a statute mile and in a foot, so every imperial rendering below is one
/// division rather than a chain of conversion constants.
const METRES_PER_MILE: f64 = 1609.344;
const METRES_PER_FOOT: f64 = 0.3048;
/// Feet below which an imperial distance is rendered in feet, and above which it is
/// rendered in tenths of a mile. 1000 ft is a little under a fifth of a mile.
const FEET_LIMIT: f64 = 1000.0;
/// Metres below which a metric distance is rendered in metres.
const METRES_LIMIT: f64 = 1000.0;

/// How distances on the cluster are written.
///
/// A choice, not a conversion: the numbers are the same distance either way, and the
/// question the vehicle answers is which of them its cluster is willing to display. The
/// truck under test is a US Ford, so [`Units::Imperial`] is the default; a head unit that
/// shows nothing for one and something for the other is a finding, which is why this is a
/// configuration key rather than a constant.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Units {
    #[default]
    Imperial,
    Metric,
}

impl Units {
    pub fn name(self) -> &'static str {
        match self {
            Units::Imperial => "imperial",
            Units::Metric => "metric",
        }
    }

    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "imperial" => Some(Units::Imperial),
            "metric" => Some(Units::Metric),
            _ => None,
        }
    }

    /// One distance as the wire carries it: a number, the unit it is counted in, and the
    /// text a display can use instead of doing the arithmetic itself.
    ///
    /// **The `_P1` unit codes are a reading, and the text is the hedge against it.**
    /// `DISTANCE_UNIT_MILES_P1` is documented upstream only as a "one-decimal precision
    /// variant", with nothing said about what the accompanying value counts; tenths is the
    /// only reading that makes the variant worth having, and it is the reading used here.
    /// A cluster that took it for whole miles would show `3 mi` where this source means
    /// `0.3 mi` — so [`Distance::text`] always spells the same distance out, and the two
    /// disagreeing on a vehicle is exactly the evidence that settles it. Whole-unit codes
    /// are used wherever they are precise enough, so the reading is only ever exercised
    /// above a fifth of a mile, where being wrong by a factor of ten is obvious rather
    /// than subtle.
    pub fn render(self, metres: f64) -> Distance {
        let metres = metres.max(0.0);
        let round10 = |v: f64| (v / 10.0).round() as i32 * 10;
        match self {
            Units::Imperial => {
                let feet = metres / METRES_PER_FOOT;
                if feet < FEET_LIMIT {
                    let value = round10(feet);
                    Distance::new(
                        value,
                        DistanceDisplayUnit::DistanceUnitFeet,
                        format!("{value} ft"),
                    )
                } else {
                    let tenths = (metres / METRES_PER_MILE * 10.0).round() as i32;
                    let text = format!("{}.{} mi", tenths / 10, tenths % 10);
                    Distance::new(tenths, DistanceDisplayUnit::DistanceUnitMilesP1, text)
                }
            }
            Units::Metric => {
                if metres < METRES_LIMIT {
                    let value = round10(metres);
                    Distance::new(
                        value,
                        DistanceDisplayUnit::DistanceUnitMeters,
                        format!("{value} m"),
                    )
                } else {
                    let tenths = (metres / 100.0).round() as i32;
                    let text = format!("{}.{} km", tenths / 10, tenths % 10);
                    Distance::new(tenths, DistanceDisplayUnit::DistanceUnitKilometersP1, text)
                }
            }
        }
    }
}

/// A distance as three consistent statements of the same thing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Distance {
    /// Counted in [`Distance::unit`]; tenths for the `_P1` unit codes.
    pub value: i32,
    /// The raw `DistanceDisplayUnit` value, not a name for it.
    pub unit: i32,
    /// The same distance written out, for a cluster that would rather show a string than
    /// interpret a unit code.
    pub text: String,
}

impl Distance {
    fn new(value: i32, unit: DistanceDisplayUnit, text: String) -> Self {
        Self {
            value,
            unit: unit as i32,
            text,
        }
    }

    /// The pinned schema's name for [`Distance::unit`], for the report. Nothing branches
    /// on it.
    pub fn unit_name(&self) -> &'static str {
        DistanceDisplayUnit::try_from(self.unit).map_or("unknown", |u| u.as_str_name())
    }
}

impl fmt::Display for Distance {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "value={} unit={} ({}) text={:?}",
            self.value,
            self.unit,
            self.unit_name(),
            self.text
        )
    }
}

/// One leg of a scripted route: the road driven along it, how long it is, and the
/// maneuver at its far end.
///
/// A leg holds the maneuver that *ends* it rather than the one that began it, because
/// that is the thing a cluster counts down to.
#[derive(Debug, Clone, PartialEq)]
pub struct Leg {
    /// The road being driven along this leg, which is what `current_road` reports.
    pub road: String,
    pub metres: f64,
    /// The raw `ManeuverType` value performed at the end of the leg.
    pub maneuver: i32,
    /// Which exit to take, for the roundabout maneuvers that carry one.
    pub roundabout_exit: Option<i32>,
    /// The instruction as a driver would read it.
    pub instruction: String,
    /// Road names the maneuver enters.
    ///
    /// Whether `NavigationStep.road_info` means the road entered or the road left is not
    /// recorded anywhere; Android's own `Step` model calls it the road the step leads
    /// onto, and that is the reading used. The road being left is on the wire anyway, as
    /// `current_road` in the distance message, so a cluster reading it the other way round
    /// shows a name that is true of the previous second rather than a wrong name.
    pub onto: Vec<String>,
}

impl Leg {
    pub fn new(road: &str, metres: f64, maneuver: i32, instruction: &str, onto: &[&str]) -> Self {
        Self {
            road: road.to_owned(),
            metres,
            maneuver,
            roundabout_exit: None,
            instruction: instruction.to_owned(),
            onto: onto.iter().map(|n| (*n).to_owned()).collect(),
        }
    }

    #[must_use]
    pub fn roundabout(mut self, exit: i32) -> Self {
        self.roundabout_exit = Some(exit);
        self
    }
}

/// A scripted route: legs in order, driven at one speed.
#[derive(Debug, Clone, PartialEq)]
pub struct Script {
    legs: Vec<Leg>,
    speed_m_s: f64,
}

/// Pace of the demonstration route, in metres per second.
///
/// Not a plausible vehicle speed for the streets it names, and not meant to be: it is
/// chosen so that every maneuver holds the cluster for between fourteen and forty-five
/// seconds, which is long enough for an operator to see it and photograph it and short
/// enough that the whole sequence, destination included, fits inside a three-minute drive.
/// Nothing downstream reads it as a speed — the script publishes distances and times, not
/// a position.
const DEMO_SPEED_M_S: f64 = 20.0;

impl Default for Script {
    fn default() -> Self {
        Self::demo()
    }
}

impl Script {
    /// A route, or `None` if it could not be driven: no legs, a leg of no length, or a
    /// speed that would divide by zero or never arrive.
    pub fn new(legs: Vec<Leg>, speed_m_s: f64) -> Option<Self> {
        let usable = !legs.is_empty()
            && legs.iter().all(|l| l.metres.is_finite() && l.metres > 0.0)
            && speed_m_s.is_finite()
            && speed_m_s > 0.0;
        usable.then_some(Self { legs, speed_m_s })
    }

    /// The demonstration route: six legs, six different maneuver classes, 3220 m.
    ///
    /// The maneuvers are chosen to span what a cluster has to distinguish rather than to
    /// be a realistic drive — an ordinary turn, a roundabout with an exit number, a slight
    /// turn, a motorway entrance, a motorway exit, and an arrival — because the question
    /// the gate asks is whether the cluster renders *a maneuver stream*, and six of one
    /// kind would answer it for one icon. The road names are invented, and the report says
    /// none of them (spec §10).
    pub fn demo() -> Self {
        use maneuver_type::Enum as M;
        let legs = vec![
            Leg::new(
                "Depot Street",
                320.0,
                M::TurnNormalRight as i32,
                "Turn right onto Mill Road",
                &["Mill Road"],
            ),
            Leg::new(
                "Mill Road",
                540.0,
                M::RoundaboutEnterAndExitCw as i32,
                "At the roundabout, take the second exit onto Quarry Lane",
                &["Quarry Lane"],
            )
            .roundabout(2),
            Leg::new(
                "Quarry Lane",
                280.0,
                M::TurnSlightLeft as i32,
                "Bear left onto Old Post Road",
                &["Old Post Road"],
            ),
            Leg::new(
                "Old Post Road",
                760.0,
                M::OnRampNormalRight as i32,
                "Take the ramp onto Route 9 East",
                &["Route 9 East"],
            ),
            Leg::new(
                "Route 9 East",
                900.0,
                M::OffRampSlightRight as i32,
                "Take the exit toward Harbor Street",
                &["Harbor Street"],
            ),
            Leg::new(
                "Harbor Street",
                420.0,
                M::DestinationLeft as i32,
                "Your destination is on the left",
                &[],
            ),
        ];
        Self {
            legs,
            speed_m_s: DEMO_SPEED_M_S,
        }
    }

    pub fn legs(&self) -> usize {
        self.legs.len()
    }

    pub fn route_metres(&self) -> f64 {
        self.legs.iter().map(|l| l.metres).sum()
    }

    /// How long the whole route takes, which is how long a session has to run for the
    /// sequence to reach its destination.
    pub fn duration(&self) -> Duration {
        Duration::from_secs_f64(self.route_metres() / self.speed_m_s)
    }

    /// What the cluster should be told after `elapsed` of driving, or `None` once the
    /// route is finished.
    pub fn at(&self, elapsed: Duration) -> Option<Guidance> {
        let travelled = self.speed_m_s * elapsed.as_secs_f64();
        let total = self.route_metres();
        let seconds = |metres: f64| (metres / self.speed_m_s).round() as i64;
        let mut start = 0.0;
        for (index, leg) in self.legs.iter().enumerate() {
            let end = start + leg.metres;
            if travelled < end {
                let step_metres = end - travelled;
                let destination_metres = total - travelled;
                return Some(Guidance {
                    leg: index,
                    maneuver: leg.maneuver,
                    roundabout_exit: leg.roundabout_exit,
                    instruction: leg.instruction.clone(),
                    onto: leg.onto.clone(),
                    current_road: leg.road.clone(),
                    step_metres,
                    step_seconds: seconds(step_metres),
                    destination_metres,
                    destination_seconds: seconds(destination_metres),
                });
            }
            start = end;
        }
        None
    }
}

/// What the cluster is told at one instant: one maneuver, how far and how long to it, and
/// how far and how long to the destination past it.
#[derive(Debug, Clone, PartialEq)]
pub struct Guidance {
    /// Index of the leg being driven. A change of this is a change of maneuver, which is
    /// what a new notification is sent for.
    pub leg: usize,
    /// The raw `ManeuverType` value, not a name for it.
    pub maneuver: i32,
    pub roundabout_exit: Option<i32>,
    pub instruction: String,
    pub onto: Vec<String>,
    pub current_road: String,
    pub step_metres: f64,
    pub step_seconds: i64,
    pub destination_metres: f64,
    pub destination_seconds: i64,
}

impl Guidance {
    /// The pinned schema's name for [`Guidance::maneuver`], for the report. Nothing
    /// branches on it: `ManeuverType` is marked `confidence: unverified` upstream, and
    /// this project has had four vendored names turn out wrong on the wire (spec §8.1).
    pub fn maneuver_name(&self) -> &'static str {
        maneuver_type::Enum::try_from(self.maneuver).map_or("unknown", |m| m.as_str_name())
    }

    /// One line for the report: the maneuver and how far away it is, and nothing that
    /// says where anybody is going.
    ///
    /// No road name, no instruction text and no destination — the P6 plan §9 puts a
    /// destination on the same footing as `sensor_location`, and the road names here being
    /// invented is not a reason to establish the habit of printing them.
    pub fn summary(&self, units: Units) -> String {
        format!(
            "maneuver={} ({}) exit={} step={} to_step_s={} to_destination_s={}",
            self.maneuver,
            self.maneuver_name(),
            self.roundabout_exit.map_or("-".into(), |e| e.to_string()),
            units.render(self.step_metres),
            self.step_seconds,
            self.destination_seconds,
        )
    }

    /// A maneuver from the app's guidance loop (P6.5).
    ///
    /// `step` is the maneuver's identity, not its position in anything: the app numbers
    /// maneuvers so that a new route never reuses an identity an old one sent, because a
    /// change of identity is what puts a new notification on the wire. No road names:
    /// BRouter's data has none (its `lookups.dat` carries no `name` tag), so the app reads
    /// them from its map and adds them with [`Guidance::with_roads`].
    #[allow(clippy::too_many_arguments)]
    pub fn live(
        step: usize,
        maneuver: i32,
        roundabout_exit: Option<i32>,
        instruction: &str,
        step_metres: f64,
        step_seconds: i64,
        destination_metres: f64,
        destination_seconds: i64,
    ) -> Self {
        let metres = |m: f64| if m.is_finite() { m.max(0.0) } else { 0.0 };
        Self {
            leg: step,
            maneuver,
            roundabout_exit: roundabout_exit.filter(|e| *e > 0),
            instruction: crate::now_playing::clean(instruction),
            onto: Vec::new(),
            current_road: String::new(),
            step_metres: metres(step_metres),
            step_seconds: step_seconds.max(0),
            destination_metres: metres(destination_metres).max(metres(step_metres)),
            destination_seconds: destination_seconds.max(step_seconds).max(0),
        }
    }
}

impl Guidance {
    /// A live maneuver's road names, from the app's map: `onto` the road the maneuver
    /// turns onto (`NavigationStep.road_info`) and `current_road` the one being driven
    /// (`current_road` in the distance message). Either may be empty, meaning the map
    /// names none: an empty `onto` sends no road name, and an empty `current_road` is
    /// left off the wire rather than sent blank, which would claim the road has no name.
    pub fn with_roads(mut self, onto: &str, current_road: &str) -> Self {
        let onto = crate::now_playing::clean(onto.trim());
        self.onto = if onto.is_empty() {
            Vec::new()
        } else {
            vec![onto]
        };
        self.current_road = crate::now_playing::clean(current_road.trim());
        self
    }
}

/// Where the cluster's maneuvers come from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Source {
    /// [`Script`]: a fixed sequence on the session's own clock (P6.2). The default,
    /// because it is the one that needs nothing from the app.
    #[default]
    Script,
    /// The app's guidance loop over a computed route, through [`GuidanceLink`] (P6.5).
    Route,
}

impl Source {
    pub fn name(self) -> &'static str {
        match self {
            Source::Script => "script",
            Source::Route => "route",
        }
    }

    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "script" => Some(Source::Script),
            "route" => Some(Source::Route),
            _ => None,
        }
    }
}

/// What the app's guidance loop says the cluster should show (P6.5).
///
/// `NavigationState` has four values and three of them mean something here: guiding is
/// `ACTIVE`, rerouting is `REROUTING`, and idle, arrived and ended are all `INACTIVE`.
/// Arrived is kept apart from idle only so the report can say a route was driven to its
/// end. Ended is kept apart from idle because it changes what happens to the cluster's
/// focus, not what the cluster is told (P7.4).
#[derive(Debug, Clone, PartialEq)]
pub enum RouteState {
    /// No route: nothing to guide. The cluster, if held, is kept for the next one.
    Idle,
    /// On a route, with the next maneuver and the distances to it.
    Guiding(Guidance),
    /// Off the route, and a new one is being computed.
    Rerouting,
    /// At the destination.
    Arrived,
    /// The driver ended the route: End route on the dashboard, Clear route on the phone,
    /// or the route ending itself after arrival (P7.4). The same `INACTIVE` as idle, and
    /// then the cluster handed back to the head unit's own navigation, so the vehicle's
    /// own guidance can have it between routes. The next route asks for it again.
    ///
    /// Idle does not do this, and is still what the app publishes with no route at the
    /// start of a session and for a route with no fix on it yet: P6.5 kept the cluster
    /// across those so a destination needs no new handshake, and nothing about them says
    /// the driver has finished with it.
    Ended,
}

impl RouteState {
    pub fn name(&self) -> &'static str {
        match self {
            RouteState::Idle => "idle",
            RouteState::Guiding(_) => "guiding",
            RouteState::Rerouting => "rerouting",
            RouteState::Arrived => "arrived",
            RouteState::Ended => "ended",
        }
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

/// The app → session hand-off for guidance, the same shape as
/// [`crate::now_playing::NowPlayingLink`]: the app publishes, the session takes snapshots
/// and puts them on the wire as the head unit's interval allows.
///
/// **Publishing is not sending.** The app publishes on every vehicle fix, far faster than
/// Ford's 400 ms floor lets anything out; the session sends the newest state it holds when
/// the interval opens, and everything in between is simply overwritten. That is the right
/// loss: a countdown only ever wants its latest value.
#[derive(Default)]
pub struct GuidanceLink {
    current: Mutex<Option<RouteState>>,
    waker: Mutex<Option<Arc<dyn Fn() + Send + Sync>>>,
}

impl fmt::Debug for GuidanceLink {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("GuidanceLink")
            .field("current", &self.snapshot().map(|s| s.name()))
            .finish_non_exhaustive()
    }
}

impl GuidanceLink {
    /// Wakes the session thread when the app publishes. Set once by the transport owner.
    pub fn set_waker(&self, waker: Arc<dyn Fn() + Send + Sync>) {
        *lock(&self.waker) = Some(waker);
    }

    /// App thread: replace what the cluster should show. Returns whether it changed.
    pub fn publish(&self, state: RouteState) -> bool {
        let changed = {
            let mut current = lock(&self.current);
            let changed = current.as_ref() != Some(&state);
            *current = Some(state);
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

    /// Session thread: what the app last published, or `None` if nothing yet.
    pub fn snapshot(&self) -> Option<RouteState> {
        lock(&self.current).clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Distance, time to the maneuver and time to the destination are three views of one
    /// position, so they cannot disagree — the property P6.1 established for position,
    /// speed and bearing.
    #[test]
    fn every_number_in_a_guidance_comes_from_one_position() {
        let script = Script::demo();
        let speed = DEMO_SPEED_M_S;
        for tenth in 0..30 {
            let elapsed = Duration::from_secs_f64(f64::from(tenth) * 5.0);
            let Some(g) = script.at(elapsed) else { break };
            assert!(
                (g.step_metres - f64::from(g.step_seconds as i32) * speed).abs() <= speed,
                "{g:?}"
            );
            assert!(
                (g.destination_metres - f64::from(g.destination_seconds as i32) * speed).abs()
                    <= speed,
                "{g:?}"
            );
            // Arriving at the destination is never nearer than arriving at the maneuver
            // before it.
            assert!(g.destination_metres >= g.step_metres, "{g:?}");
        }
    }

    /// The countdown falls, the legs advance in order, and the route ends at a
    /// destination rather than running forever.
    #[test]
    fn the_demo_route_counts_down_through_its_legs_to_a_destination() {
        let script = Script::demo();
        assert_eq!(script.legs(), 6);
        assert!((script.route_metres() - 3220.0).abs() < 0.5);
        let start = script.at(Duration::ZERO).expect("a route starts somewhere");
        assert_eq!(start.leg, 0);
        assert!((start.step_metres - 320.0).abs() < 0.5);
        assert!((start.destination_metres - 3220.0).abs() < 0.5);

        let mut legs_seen = vec![0usize];
        let mut previous = start;
        for step in 1..=160 {
            let Some(g) = script.at(Duration::from_secs(step)) else {
                break;
            };
            assert!(g.leg >= previous.leg, "legs went backwards: {g:?}");
            if g.leg != previous.leg {
                legs_seen.push(g.leg);
            } else {
                assert!(g.step_metres < previous.step_metres, "{g:?} {previous:?}");
            }
            assert!(g.destination_metres < previous.destination_metres);
            previous = g;
        }
        assert_eq!(legs_seen, vec![0, 1, 2, 3, 4, 5]);
        assert_eq!(
            previous.maneuver,
            maneuver_type::Enum::DestinationLeft as i32
        );
        // A finished route says so rather than repeating its last maneuver forever.
        assert_eq!(script.at(script.duration()), None);
        assert_eq!(script.at(Duration::from_secs(3600)), None);
    }

    /// A roundabout carries its exit number and nothing else does, so a cluster is never
    /// told to take the second exit off a left turn.
    #[test]
    fn only_a_roundabout_carries_an_exit_number() {
        let script = Script::demo();
        let mut exits = 0;
        for second in 0..=160 {
            let Some(g) = script.at(Duration::from_secs(second)) else {
                break;
            };
            match g.maneuver == maneuver_type::Enum::RoundaboutEnterAndExitCw as i32 {
                true => exits += i32::from(g.roundabout_exit == Some(2)),
                false => assert_eq!(g.roundabout_exit, None, "{g:?}"),
            }
        }
        assert!(exits > 0, "the roundabout leg was never driven");
    }

    /// The value, the unit and the text are three statements of one distance; a cluster
    /// reading any one of them must land in the same place.
    #[test]
    fn a_rendered_distance_says_the_same_thing_three_ways() {
        use DistanceDisplayUnit as U;
        for (metres, units, value, unit, text) in [
            (0.0, Units::Imperial, 0, U::DistanceUnitFeet, "0 ft"),
            (30.48, Units::Imperial, 100, U::DistanceUnitFeet, "100 ft"),
            // Just under 1000 ft stays in feet; just over becomes tenths of a mile.
            (304.0, Units::Imperial, 1000, U::DistanceUnitFeet, "1000 ft"),
            (321.9, Units::Imperial, 2, U::DistanceUnitMilesP1, "0.2 mi"),
            (
                1609.344,
                Units::Imperial,
                10,
                U::DistanceUnitMilesP1,
                "1.0 mi",
            ),
            (
                3218.7,
                Units::Imperial,
                20,
                U::DistanceUnitMilesP1,
                "2.0 mi",
            ),
            (0.0, Units::Metric, 0, U::DistanceUnitMeters, "0 m"),
            (344.0, Units::Metric, 340, U::DistanceUnitMeters, "340 m"),
            (999.0, Units::Metric, 1000, U::DistanceUnitMeters, "1000 m"),
            (
                1000.0,
                Units::Metric,
                10,
                U::DistanceUnitKilometersP1,
                "1.0 km",
            ),
            (
                3220.0,
                Units::Metric,
                32,
                U::DistanceUnitKilometersP1,
                "3.2 km",
            ),
        ] {
            let rendered = units.render(metres);
            assert_eq!(
                (rendered.value, rendered.unit, rendered.text.as_str()),
                (value, unit as i32, text),
                "{metres} m as {}",
                units.name()
            );
        }
        // Never a negative distance on a cluster, whatever arithmetic produced it.
        assert_eq!(Units::Imperial.render(-5.0).value, 0);
        assert_eq!(Units::Metric.render(f64::NAN).value, 0);
        for units in [Units::Imperial, Units::Metric] {
            assert_eq!(Units::from_name(units.name()), Some(units));
        }
        assert_eq!(Units::from_name("nautical"), None);
    }

    /// A route that cannot be driven is refused at construction rather than dividing by
    /// zero in the middle of a drive.
    #[test]
    fn an_undriveable_script_is_refused() {
        let leg = || Leg::new("Road", 100.0, 1, "Go", &["Road"]);
        assert!(Script::new(vec![leg()], 10.0).is_some());
        assert!(Script::new(Vec::new(), 10.0).is_none());
        assert!(Script::new(vec![leg()], 0.0).is_none());
        assert!(Script::new(vec![leg()], -1.0).is_none());
        assert!(Script::new(vec![leg()], f64::NAN).is_none());
        assert!(Script::new(vec![Leg::new("R", 0.0, 1, "Go", &[])], 10.0).is_none());
        assert!(Script::new(vec![Leg::new("R", f64::INFINITY, 1, "Go", &[])], 10.0).is_none());
    }

    /// The report line carries the maneuver and the distance, and nothing that says where
    /// the vehicle is going (spec §10).
    #[test]
    fn a_guidance_summary_names_no_road_and_no_destination() {
        let g = Script::demo().at(Duration::ZERO).expect("a first maneuver");
        let summary = g.summary(Units::Imperial);
        assert!(summary.contains("TURN_NORMAL_RIGHT"), "{summary}");
        assert!(!summary.contains("Mill Road"), "{summary}");
        assert!(!summary.contains("Depot"), "{summary}");
        assert!(!summary.contains("Harbor"), "{summary}");
    }

    /// A live maneuver is bounded like everything else that reaches a head unit's screen,
    /// and its numbers cannot contradict each other however the app computed them.
    #[test]
    fn a_live_maneuver_is_bounded_and_self_consistent() {
        let g = Guidance::live(7, 8, Some(0), "Turn\nright", -3.0, -1, 10.0, 0);
        assert_eq!(g.leg, 7);
        assert_eq!(g.instruction, "Turn right");
        assert_eq!(g.roundabout_exit, None, "exit 0 is not an exit");
        assert_eq!(g.step_metres, 0.0);
        assert_eq!(g.step_seconds, 0);
        assert!(g.onto.is_empty() && g.current_road.is_empty());
        let g = Guidance::live(1, 32, Some(2), "", 500.0, 40, 100.0, 10);
        assert_eq!(g.roundabout_exit, Some(2));
        // The destination is never nearer than the maneuver before it.
        assert_eq!(g.destination_metres, 500.0);
        assert_eq!(g.destination_seconds, 40);
        let g = Guidance::live(1, 1, None, "", f64::NAN, 0, f64::INFINITY, 0);
        assert_eq!((g.step_metres, g.destination_metres), (0.0, 0.0));
        let long = "a".repeat(crate::now_playing::TEXT_LIMIT * 2);
        let g = Guidance::live(1, 1, None, &long, 0.0, 0, 0.0, 0);
        assert_eq!(
            g.instruction.chars().count(),
            crate::now_playing::TEXT_LIMIT
        );
    }

    /// Road names are bounded like the instruction, and an empty one is no name at all
    /// rather than a blank one on the wire.
    #[test]
    fn a_live_maneuver_carries_the_road_names_it_is_given() {
        let g = Guidance::live(
            1,
            8,
            None,
            "Turn right onto Main Street",
            100.0,
            10,
            200.0,
            20,
        )
        .with_roads("Main Street", " Elm\nAvenue ");
        assert_eq!(g.onto, vec!["Main Street".to_owned()]);
        assert_eq!(g.current_road, "Elm Avenue");
        let g = Guidance::live(1, 8, None, "Turn right", 100.0, 10, 200.0, 20).with_roads("", "  ");
        assert!(g.onto.is_empty() && g.current_road.is_empty());
        let long = "a".repeat(crate::now_playing::TEXT_LIMIT * 2);
        let g = Guidance::live(1, 8, None, "", 0.0, 0, 0.0, 0).with_roads(&long, &long);
        assert_eq!(g.onto[0].chars().count(), crate::now_playing::TEXT_LIMIT);
        assert_eq!(
            g.current_road.chars().count(),
            crate::now_playing::TEXT_LIMIT
        );
        // Still nothing that says where in the report.
        let g = Guidance::live(
            1,
            8,
            None,
            "Turn right onto Main Street",
            100.0,
            10,
            200.0,
            20,
        )
        .with_roads("Main Street", "Elm Avenue");
        let summary = g.summary(Units::Imperial);
        assert!(
            !summary.contains("Main") && !summary.contains("Elm"),
            "{summary}"
        );
    }

    #[test]
    fn sources_round_trip_through_their_names() {
        for source in [Source::Script, Source::Route] {
            assert_eq!(Source::from_name(source.name()), Some(source));
        }
        assert_eq!(Source::from_name("live"), None);
        assert_eq!(Source::default(), Source::Script);
    }

    #[test]
    fn the_guidance_link_wakes_the_session_only_on_a_change() {
        let woken = Arc::new(Mutex::new(0u32));
        let link = GuidanceLink::default();
        assert_eq!(link.snapshot(), None);
        let counter = woken.clone();
        link.set_waker(Arc::new(move || *lock(&counter) += 1));
        let g = Guidance::live(1, 8, None, "Turn right", 100.0, 10, 200.0, 20);
        assert!(link.publish(RouteState::Guiding(g.clone())));
        assert!(!link.publish(RouteState::Guiding(g)));
        assert!(link.publish(RouteState::Rerouting));
        assert_eq!(*lock(&woken), 2);
        assert_eq!(link.snapshot(), Some(RouteState::Rerouting));
    }
}
