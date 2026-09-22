//! Head-unit input for the projection demonstration (spec §8.3). Touch coordinates are
//! validated and mapped into video frame pixels. Which space they arrive in is
//! [`TouchSpace`]: Ford SYNC 4.0 sends them in the selected configuration's frame, not in
//! the touch screen it advertises, so the advertised size is not a scale factor by default,
//! and it counts them from the content rectangle's origin rather than the frame's
//! (docs/references.md, "Touch coordinate space"). Nothing here injects events into Android.
use crate::discovery::Rect;
use aap_proto::data::TouchEvent;
use std::fmt;

/// Most simultaneous pointers accepted in one event.
pub const POINTER_LIMIT: usize = 10;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TouchAction {
    Down,
    Up,
    Move,
    Cancel,
    PointerDown,
    PointerUp,
}

impl TouchAction {
    /// open-android-auto `TouchAction`: PRESS 0, RELEASE 1, DRAG 2, POINTER_DOWN 5,
    /// POINTER_UP 6. Android's ACTION_CANCEL (3) is not in the pinned enum but is accepted,
    /// because the enum mirrors Android's MotionEvent actions.
    pub fn from_wire(value: i32) -> Option<Self> {
        Some(match value {
            0 => TouchAction::Down,
            1 => TouchAction::Up,
            2 => TouchAction::Move,
            3 => TouchAction::Cancel,
            5 => TouchAction::PointerDown,
            6 => TouchAction::PointerUp,
            _ => return None,
        })
    }

    pub fn name(self) -> &'static str {
        match self {
            TouchAction::Down => "down",
            TouchAction::Up => "up",
            TouchAction::Move => "move",
            TouchAction::Cancel => "cancel",
            TouchAction::PointerDown => "pointer-down",
            TouchAction::PointerUp => "pointer-up",
        }
    }
}

/// One pointer in video frame pixels, with the head unit's raw coordinates.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Pointer {
    pub id: u32,
    pub x: u32,
    pub y: u32,
    pub raw_x: u32,
    pub raw_y: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Touch {
    pub action: TouchAction,
    /// Pointer the action applies to (from `action_index`).
    pub action_pointer: u32,
    pub pointers: Vec<Pointer>,
    /// The head unit's event timestamp (microseconds, its clock).
    pub timestamp_us: u64,
}

impl fmt::Display for Touch {
    /// `action=down pointer=0 pointers=0:400:240:960:441,...` (id, video x, video y, raw x,
    /// raw y).
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "action={} pointer={} pointers=",
            self.action.name(),
            self.action_pointer
        )?;
        for (i, p) in self.pointers.iter().enumerate() {
            if i > 0 {
                write!(f, ",")?;
            }
            write!(f, "{}:{}:{}:{}:{}", p.id, p.x, p.y, p.raw_x, p.raw_y)?;
        }
        Ok(())
    }
}

/// The coordinate space a head unit sends touch events in.
///
/// Two vehicle measurements settled this, and they are independent. The *scale* came from G4
/// (report da9398a5): with 800x480 selected Ford SYNC 4.0 advertised a 1920x882 touch screen
/// but sent coordinates inside 800x480, so treating the advertised size as the space
/// compressed every touch into the top-left corner — 21 taps spread over the scene all
/// landed on button 1. Its advertised size describes the native 1080p layout (full frame
/// width by content height) and is not restated per configuration. One touch unit is one
/// frame pixel in every space here except [`TouchSpace::Advertised`].
///
/// The *origin* came from the calibration in report 5c9ac430: the operator pressed a cross
/// drawn at each of four known frame pixels and the head unit reported them a uniform
/// 39 +/- 6 px to the left, at unit scale (0.994), with no sign flip between the left and
/// right columns. That rules out both a frame-origin grid (predicting no offset) and a grid
/// stretched over the content rectangle (predicting +-18px, opposite signs per column). The
/// offset is the 37px left inset: Ford's grid has the frame's dimensions but starts at the
/// content rectangle's origin, so a press must be shifted back by the inset.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum TouchSpace {
    /// The selected configuration's whole frame, one touch unit per frame pixel. The
    /// spec-compliant reading, and what a unit advertising no margins sends; Ford SYNC 4.0
    /// differs only by the inset, so this is [`TouchSpace::Content`] whenever the margins
    /// are zero.
    Frame,
    /// The frame's grid with its origin at the content rectangle, one touch unit per frame
    /// pixel. What Ford SYNC 4.0 sends, and the default. Note the asymmetry the vehicle
    /// forced: the grid's *extent* is the frame's (Ford sends x up to 791 on an 800-wide
    /// frame, past the 726-wide content rectangle), while its *origin* is the content
    /// rectangle's. So the far end of the grid addresses pixels past the frame, which
    /// [`TouchMap::map`] clamps onto the frame's last pixel.
    #[default]
    Content,
    /// The advertised touch screen, stretched onto the content rectangle. What the
    /// open-android-auto head-unit notes describe; no unit has been observed doing it.
    Advertised,
}

impl TouchSpace {
    pub fn name(self) -> &'static str {
        match self {
            TouchSpace::Frame => "frame",
            TouchSpace::Content => "content",
            TouchSpace::Advertised => "advertised",
        }
    }
}

/// Maps head-unit touch coordinates into video frame pixels.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TouchMap {
    pub space: TouchSpace,
    /// Extent of the coordinate space the head unit sends; coordinates outside it are
    /// rejected.
    pub size: (u32, u32),
    /// Frame rectangle that space covers. It can reach past the frame: see
    /// [`TouchSpace::Content`].
    pub target: Rect,
    /// The selected configuration's frame, which every mapped coordinate stays inside.
    pub frame: (u32, u32),
}

impl TouchMap {
    /// The map for `space`, given the head unit's advertised touch screen and the selected
    /// configuration's frame size and content rectangle. `None` when the resulting space or
    /// rectangle is empty, which for [`TouchSpace::Advertised`] includes a nonpositive
    /// advertised size.
    pub fn new(
        space: TouchSpace,
        advertised: (i32, i32),
        frame: (u32, u32),
        content: Rect,
    ) -> Option<Self> {
        let (size, target) = match space {
            TouchSpace::Frame => (
                frame,
                Rect {
                    x: 0,
                    y: 0,
                    width: frame.0,
                    height: frame.1,
                },
            ),
            // Only the origin is taken from the content rectangle, so an empty one still has
            // to be refused here rather than by the emptiness check below.
            TouchSpace::Content if content.width == 0 || content.height == 0 => return None,
            // The frame's grid, moved to the content origin: the dimensions come from the
            // frame and only the origin from the content rectangle.
            TouchSpace::Content => (
                frame,
                Rect {
                    x: content.x,
                    y: content.y,
                    width: frame.0,
                    height: frame.1,
                },
            ),
            TouchSpace::Advertised => {
                let w = u32::try_from(advertised.0).ok()?;
                let h = u32::try_from(advertised.1).ok()?;
                ((w, h), content)
            }
        };
        // An empty frame has no pixel to map onto whatever the space, and `map` clamps to
        // `frame - 1`.
        (size.0 > 0
            && size.1 > 0
            && target.width > 0
            && target.height > 0
            && frame.0 > 0
            && frame.1 > 0)
            .then_some(Self {
                space,
                size,
                target,
                frame,
            })
    }

    /// Video frame pixel for a touch coordinate, or `None` outside the touch space.
    ///
    /// The extent is an inclusive bound. Ford reports an edge press as exactly the frame
    /// dimension: report 363a4d96 dropped 39 of 414 events, the last of them
    /// `touch 784x480` against a 480-high frame, all of them the corner and edge presses
    /// the G5 procedure asks for. So the head unit's grid runs `0..=size`, one value wider
    /// than the pixels it addresses, and the far edge maps to the last pixel of the target.
    ///
    /// A [`TouchSpace::Content`] target starts at the content origin and is as wide as the
    /// frame, so its far end addresses pixels past the frame — the strip of head unit that
    /// shows nothing of this frame. Those land on the frame's last pixel rather than being
    /// rejected: the press happened, and the nearest pixel is the honest place for it.
    pub fn map(&self, x: u32, y: u32) -> Option<(u32, u32)> {
        let (w, h) = self.size;
        if x > w || y > h {
            return None;
        }
        let t = self.target;
        // Interior coordinates keep the plain ratio (an identity for the frame space); only
        // a press at the very edge needs the clamp onto the last pixel.
        let scale = |v: u32, extent: u32, span: u32| {
            (u64::from(v) * u64::from(span) / u64::from(extent)).min(u64::from(span - 1))
        };
        let vx = u64::from(t.x) + scale(x, w, t.width);
        let vy = u64::from(t.y) + scale(y, h, t.height);
        Some((
            vx.min(u64::from(self.frame.0 - 1)) as u32,
            vy.min(u64::from(self.frame.1 - 1)) as u32,
        ))
    }

    /// Validates and maps one touch event. Errors describe why it was rejected.
    pub fn touch(&self, event: &TouchEvent, timestamp_us: u64) -> Result<Touch, String> {
        let raw_action = event.touch_action.unwrap_or(0);
        let action = TouchAction::from_wire(raw_action)
            .ok_or_else(|| format!("unknown touch action {raw_action}"))?;
        let locations = &event.touch_location;
        if locations.is_empty() || locations.len() > POINTER_LIMIT {
            return Err(format!("{} touch pointers", locations.len()));
        }
        let mut pointers = Vec::with_capacity(locations.len());
        for location in locations {
            if pointers
                .iter()
                .any(|p: &Pointer| p.id == location.pointer_id)
            {
                return Err(format!("duplicate pointer id {}", location.pointer_id));
            }
            let (x, y) = self.map(location.x, location.y).ok_or_else(|| {
                format!(
                    "touch {}x{} outside the {}x{} {} touch space",
                    location.x,
                    location.y,
                    self.size.0,
                    self.size.1,
                    self.space.name()
                )
            })?;
            pointers.push(Pointer {
                id: location.pointer_id,
                x,
                y,
                raw_x: location.x,
                raw_y: location.y,
            });
        }
        let index = event.action_index.unwrap_or(0) as usize;
        let action_pointer = pointers
            .get(index)
            .ok_or_else(|| format!("action index {index} without a pointer"))?
            .id;
        Ok(Touch {
            action,
            action_pointer,
            pointers,
            timestamp_us,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Ford SYNC 4.0 with its 800x480 configuration selected: a 1920x882 advertised touch
    /// screen, 74x0 margins, and symmetric 37px insets.
    fn ford() -> ((i32, i32), (u32, u32), Rect) {
        (
            (1920, 882),
            (800, 480),
            Rect {
                x: 37,
                y: 0,
                width: 726,
                height: 480,
            },
        )
    }

    #[test]
    fn each_space_maps_its_own_rectangle() {
        let (advertised, frame, content) = ford();
        let map = |space| TouchMap::new(space, advertised, frame, content).unwrap();

        // The frame space is the identity.
        let f = map(TouchSpace::Frame);
        assert_eq!(
            (f.size, f.map(0, 0), f.map(799, 479)),
            (frame, Some((0, 0)), Some((799, 479)))
        );
        assert_eq!(f.map(400, 240), Some((400, 240)));

        // The content space is the frame's grid shifted right by the 37px inset, and is the
        // default: its extent is the frame's, not the content rectangle's.
        let c = map(TouchSpace::Content);
        assert_eq!(TouchSpace::default(), TouchSpace::Content);
        assert_eq!(
            (c.size, c.map(0, 0), c.map(400, 240)),
            (frame, Some((37, 0)), Some((437, 240)))
        );
        // Margin height is 0, so the vertical axis is the identity.
        assert_eq!(c.map(400, 0).map(|p| p.1), Some(0));
        // Past the content rectangle's 726 width, where the frame space still has pixels.
        assert_eq!(c.map(725, 479), Some((762, 479)));
        // The last 37 units address the strip of head unit past the frame, and clamp.
        assert_eq!(
            (c.map(762, 0), c.map(763, 0), c.map(800, 0)),
            (Some((799, 0)), Some((799, 0)), Some((799, 0)))
        );

        // The advertised space stretches 1920x882 onto the content rectangle.
        let a = map(TouchSpace::Advertised);
        assert_eq!(
            (a.size, a.map(0, 0), a.map(1919, 881)),
            ((1920, 882), Some((37, 0)), Some((762, 479)))
        );
    }

    #[test]
    fn the_far_edge_is_inclusive_and_past_it_is_rejected() {
        let (advertised, frame, content) = ford();
        let f = TouchMap::new(TouchSpace::Frame, advertised, frame, content).unwrap();
        // A press at exactly the frame dimension is the far edge, not an error: Ford sends
        // it (report 363a4d96, `touch 784x480`) and it belongs on the last pixel.
        assert_eq!(
            (f.map(800, 0), f.map(0, 480)),
            (Some((799, 0)), Some((0, 479)))
        );
        assert_eq!(f.map(800, 480), Some((799, 479)));
        // One past the edge still is an error.
        assert_eq!((f.map(801, 0), f.map(0, 481)), (None, None));
        // Ford's advertised size is not the frame's: under the frame space its far corner
        // is out of range rather than silently rescaled.
        assert_eq!(f.map(1919, 881), None);
        // The interior is untouched by the clamp.
        assert_eq!(
            (f.map(400, 240), f.map(799, 479)),
            (Some((400, 240)), Some((799, 479)))
        );
    }

    #[test]
    fn the_vehicle_tap_that_exposed_the_wrong_space() {
        // G4, report da9398a5: the operator pressed button 4 (centre 577,379 in frame
        // pixels) and the head unit sent 525,364. Stretching the advertised 1920x882 onto
        // the content rectangle put that in button 1's quadrant; the frame space does not.
        let (advertised, frame, content) = ford();
        let stretched = TouchMap::new(TouchSpace::Advertised, advertised, frame, content).unwrap();
        assert_eq!(stretched.map(525, 364), Some((235, 198)));
        let f = TouchMap::new(TouchSpace::Frame, advertised, frame, content).unwrap();
        assert_eq!(f.map(525, 364), Some((525, 364)));
    }

    /// G5 dry run, report 5c9ac430: the operator pressed the calibration cross drawn at each
    /// button centre and the head unit reported these four pairs. They are the evidence for
    /// the content origin, so they are the test for it.
    #[test]
    fn the_vehicle_calibration_that_settled_the_origin() {
        let (advertised, frame, content) = ford();
        let pressed = [
            ((223, 196), (192, 203)),
            ((577, 196), (533, 204)),
            ((223, 379), (178, 388)),
            ((577, 379), (541, 386)),
        ];
        let f = TouchMap::new(TouchSpace::Frame, advertised, frame, content).unwrap();
        let c = TouchMap::new(TouchSpace::Content, advertised, frame, content).unwrap();
        for ((tx, ty), (rx, ry)) in pressed {
            let (fx, _) = f.map(rx, ry).unwrap();
            let (cx, cy) = c.map(rx, ry).unwrap();
            // The frame space is out by the inset, the same way in both columns: a grid
            // stretched over the content rectangle would be out the other way at x=577.
            assert!(
                (31..=45).contains(&(tx - fx as i32)),
                "frame space at {tx},{ty}: {fx}"
            );
            // The content space leaves only what the operator's aim can account for, the
            // size of the vertical error below.
            assert!(
                (cx as i32 - tx).abs() <= 8,
                "content space at {tx},{ty}: {cx}"
            );
            // Margin height is 0, so no candidate mapping moves y at all. The 7..9px the
            // operator pressed low is aim, and is left alone.
            assert_eq!(cy, ry);
            assert!((7..=9).contains(&(ry as i32 - ty)));
        }
    }

    #[test]
    fn an_empty_rectangle_or_advertised_size_has_no_map() {
        let empty = Rect {
            x: 0,
            y: 0,
            width: 0,
            height: 0,
        };
        let (advertised, frame, content) = ford();
        assert!(TouchMap::new(TouchSpace::Content, advertised, frame, empty).is_none());
        assert!(TouchMap::new(TouchSpace::Advertised, (0, 882), frame, content).is_none());
        assert!(TouchMap::new(TouchSpace::Advertised, (-1, 882), frame, content).is_none());
        // The frame space does not depend on the advertised size.
        assert!(TouchMap::new(TouchSpace::Frame, (0, 0), frame, content).is_some());
        assert!(TouchMap::new(TouchSpace::Frame, advertised, (0, 480), content).is_none());
    }
}
