// Controlled lab receiver for OpenAutoAndroid (GPL-3.0-only). Accepts one TCP
// connection and runs the pinned OpenAuto Prodigy receiver session over it, requesting
// a configurable protocol version and recording receiver-side state and message traces.
// TCP is a lab transport to an instrumented receiver, not wireless Android Auto.
//
// With --projection it also registers Prodigy's head-unit video, input, sensor,
// now-playing and (with --audio) PCM audio handlers, decodes the received H.264 with
// libavcodec, and runs a script:
// tap each button of the source's test scene and check that the decoded picture changes
// to that button's color, press a head-unit button, and optionally drop video focus,
// withhold acknowledgements, or (--script transport) press media buttons and check that
// both the now-playing channel and the decoded picture follow. With --audio it also
// measures the received PCM against the tone the source says it is sending, which is the
// P5.3 round-trip check; --underflow-after injects 0x800B, which no open-source head unit
// sends. With --script track (P6.1) it instead plays a moving synthetic position into the
// sensor channel and measures whether the projected map pane follows it, against the half
// of the dashboard that must stay still. With --script guidance (P6.5) the vehicle waits at
// the kerb until the source puts a route on the cluster, then drives --track-file - real
// road geometry that follows the route, misses a turn, and reaches the destination another
// way - while the receiver records what the cluster is told. With --script daynight
// (P7.6) the head unit starts in day, flips its NIGHT_DATA to night, back to day and to
// night again, and measures the map pane frame by frame across each flip: that it changed
// theme, how long that took, and whether any picture in between was blank or half-drawn.
// --night sets what NIGHT_DATA says under any other script (night by default, as before).
// --position moves the fixed fix and the track's start (P11: downtown Austin for the Texas
// data). With --script track and --poi (P11.5) the parked map must show the lit POI kinds'
// markers, by colour, in both palettes, and a tap on one must open its card in the flex tile,
// which Close must close, before the vehicle departs. With --poi-ahead (P11.5a) it waits, parked,
// for the lab script's /out/poi-ahead-go (a route is being followed), presses that kind's button
// where it sits under the turn card, waits for the search-ahead card in the flex tile, renders it,
// and presses Add stop on it, before the vehicle departs.
// With --script controls (P7.2, P7.3) it presses the dashboard's zoom bubbles and header
// icons - +, +, -, the notification pause, day/night - and checks that each press changed
// the picture it should: the map pane for a zoom, the pause icon, and the header's palette.
// With --script calls (P7.8, P7.9) it waits for notifications in the flex tile, taps the
// first row, presses the notification pause twice, then waits for a call's green Answer,
// presses it, and with --call-controls full presses Mute, Keypad, three keys, Close and
// Hang up (basic: Hang up alone), checking each press in the picture.
// With --script speech (P8.1) it waits for notifications in the flex tile and taps the
// first row three times, a few seconds apart - read it, stop it, read it again - and
// reports how lit the row still is a few seconds after each tap, after the tap's own
// flash; the app's report says what each tap did. It listens to nothing.
// With --script weather (P10) it presses the header's weather item and checks the flex tile
// open and close (a second press, a press on the view, the 30 s timeout, a call), an alert
// take the tile over a notification and two presses dismiss it, and the header's glyph, its
// warning mark and the text's clearance from it; it hands off with the lab through two files.
// That is the independent decode, input, audio, map-motion, guidance and day/night
// evidence for P4, P5.1, P5.3, P6.1, P6.5 and P7.6; it proves nothing about a factory
// head unit.
#include <QCommandLineParser>
#include <QCoreApplication>
#include <QDateTime>
#include <QElapsedTimer>
#include <QFile>
#include <QHostAddress>
#include <QPoint>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTextStream>
#include <QTimer>

#include <oaa/Channel/ControlChannel.hpp>
#include <oaa/HU/Handlers/AudioChannelHandler.hpp>
#include <oaa/HU/Handlers/InputChannelHandler.hpp>
#include <oaa/HU/Handlers/MediaStatusChannelHandler.hpp>
#include <oaa/HU/Handlers/NavigationChannelHandler.hpp>
#include <oaa/HU/Handlers/SensorChannelHandler.hpp>
#include <oaa/HU/Handlers/VideoChannelHandler.hpp>
#include <oaa/Messenger/ProtocolLogger.hpp>
#include <oaa/Session/AASession.hpp>
#include <oaa/Transport/TCPTransport.hpp>

#include "oaa/av/AVMediaAckIndicationMessage.pb.h"
#include "oaa/common/StatusEnum.pb.h"
#include "oaa/control/ChannelDescriptorData.pb.h"
#include "oaa/navigation/NavigationFocusRequestMessage.pb.h"
#include "oaa/sensor/SensorEventIndicationMessage.pb.h"
#include "oaa/sensor/SensorStartRequestMessage.pb.h"
#include "oaa/sensor/SensorStartResponseMessage.pb.h"
#include "oaa/sensor/SensorTypeEnum.pb.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
}

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace {

const char* stateName(oaa::SessionState state)
{
    switch (state) {
    case oaa::SessionState::Idle: return "Idle";
    case oaa::SessionState::Connecting: return "Connecting";
    case oaa::SessionState::VersionExchange: return "VersionExchange";
    case oaa::SessionState::TLSHandshake: return "TLSHandshake";
    case oaa::SessionState::ServiceDiscovery: return "ServiceDiscovery";
    case oaa::SessionState::Active: return "Active";
    case oaa::SessionState::ShuttingDown: return "ShuttingDown";
    case oaa::SessionState::Disconnected: return "Disconnected";
    }
    return "Unknown";
}

const char* reasonName(oaa::DisconnectReason reason)
{
    switch (reason) {
    case oaa::DisconnectReason::Normal: return "Normal";
    case oaa::DisconnectReason::PingTimeout: return "PingTimeout";
    case oaa::DisconnectReason::TransportError: return "TransportError";
    case oaa::DisconnectReason::VersionMismatch: return "VersionMismatch";
    case oaa::DisconnectReason::Timeout: return "Timeout";
    case oaa::DisconnectReason::UserRequested: return "UserRequested";
    case oaa::DisconnectReason::HandshakeError: return "HandshakeError";
    case oaa::DisconnectReason::TlsError: return "TlsError";
    case oaa::DisconnectReason::ProtocolError: return "ProtocolError";
    }
    return "Unknown";
}

QByteArray serialized(const google::protobuf::MessageLite& message)
{
    std::string bytes;
    message.SerializeToString(&bytes);
    return QByteArray(bytes.data(), static_cast<int>(bytes.size()));
}

struct Yuv {
    int y = 0;
    int u = 0;
    int v = 0;
};

// BT.601 limited range, matching the source scene's conversion.
Yuv fromRgb(int r, int g, int b)
{
    auto clamp = [](int value) { return std::max(1, std::min(254, value)); };
    return Yuv{clamp((66 * r + 129 * g + 25 * b + 128) / 256 + 16),
               clamp((-38 * r - 74 * g + 112 * b + 128) / 256 + 128),
               clamp((112 * r - 94 * g - 18 * b + 128) / 256 + 128)};
}

// The scene's button colors (tools/source-harness/src/scene.rs and the app's DemoScene).
Yuv buttonColor(int index)
{
    switch (index) {
    case 0: return fromRgb(200, 40, 40);
    case 1: return fromRgb(40, 180, 60);
    case 2: return fromRgb(50, 90, 220);
    default: return fromRgb(220, 190, 40);
    }
}

// The scene layout inside the content rectangle, as the source draws it.
struct Layout {
    int x = 0, y = 0, width = 0, height = 0;
    int header() const { return height / 5; }
    int margin() const { return std::max(2, width / 40); }
    int buttonWidth() const { return (width - 3 * margin()) / 2; }
    int buttonHeight() const { return (height - (header() + 3 * margin())) / 2; }
    QPoint origin(int index) const
    {
        const int col = index % 2, row = index / 2;
        return QPoint(x + margin() + col * (buttonWidth() + margin()),
                      y + header() + margin() + row * (buttonHeight() + margin()));
    }
    QPoint center(int index) const
    {
        return origin(index) + QPoint(buttonWidth() / 2, buttonHeight() / 2);
    }
    /// The color patch the scene keeps free of text and of the touch crosshair.
    QPoint sample(int index) const
    {
        return origin(index) + QPoint(buttonWidth() / 4, buttonHeight() * 3 / 4);
    }
    /// Middle of the now-playing swatch, in the band between the header and the first row
    /// of buttons (tools/source-harness/src/scene.rs, Layout::transport_swatch).
    QPoint transportSample() const
    {
        return QPoint(x + margin() + width / 16, y + header() + margin() / 2);
    }
};

// Playback-state colors of the now-playing swatch (scene.rs, transport_color). Prodigy's
// MediaStatusChannelHandler numbers the states Stopped 1, Playing 2, Paused 3.
Yuv transportColor(int state)
{
    switch (state) {
    case 2: return fromRgb(40, 200, 120);
    case 3: return fromRgb(230, 160, 30);
    case 1: return fromRgb(90, 90, 90);
    default: return fromRgb(190, 40, 130);
    }
}

/// The fraction of pixels differing by more than `threshold`, or -1 when the two
/// samples cannot be compared at all.
double changedFraction(const QByteArray& before, const QByteArray& after, int threshold)
{
    if (before.isEmpty() || before.size() != after.size())
        return -1.0;
    qsizetype changed = 0;
    const auto* a = reinterpret_cast<const unsigned char*>(before.constData());
    const auto* b = reinterpret_cast<const unsigned char*>(after.constData());
    for (qsizetype i = 0; i < before.size(); ++i) {
        if (std::abs(static_cast<int>(a[i]) - static_cast<int>(b[i])) > threshold)
            ++changed;
    }
    return static_cast<double>(changed) / static_cast<double>(before.size());
}

/// Every `step`th pixel of every `step`th row of a `width`-wide luma rectangle (P7.6).
///
/// The day/night script keeps one of these per decoded picture across a flip, so that
/// each picture can be compared with the one the flip settled on, which is only known
/// afterwards. A sixteenth of the pixels is plenty to tell "the old theme", "the new
/// theme" and "half of one" apart, at a sixteenth of the memory.
QByteArray thinLuma(const QByteArray& luma, int width, int step)
{
    if (luma.isEmpty() || width <= 0 || step <= 0)
        return QByteArray();
    const int height = static_cast<int>(luma.size() / width);
    QByteArray out;
    out.reserve((width / step + 1) * (height / step + 1));
    for (int y = 0; y < height; y += step)
        for (int x = 0; x < width; x += step)
            out.append(luma[static_cast<qsizetype>(y) * width + x]);
    return out;
}

/// Mean and standard deviation of a luma sample. A map pane with roads on it has a
/// spread of tens of levels; a pane showing nothing but its background has almost none.
void lumaStats(const QByteArray& luma, double& mean, double& stddev)
{
    mean = 0.0;
    stddev = 0.0;
    if (luma.isEmpty())
        return;
    const auto* p = reinterpret_cast<const unsigned char*>(luma.constData());
    double sum = 0.0, squares = 0.0;
    for (qsizetype i = 0; i < luma.size(); ++i) {
        sum += p[i];
        squares += static_cast<double>(p[i]) * p[i];
    }
    const double n = static_cast<double>(luma.size());
    mean = sum / n;
    stddev = std::sqrt(std::max(0.0, squares / n - mean * mean));
}

/// Two rectangles of the projected dashboard (P5.5), mirroring `DashboardLayout` in
/// app/src/main/kotlin/org/openautoandroid/source/Dashboard.kt field for field.
///
/// The second one is the point. A check that only watched the map pane would pass on a
/// picture where every pixel changes every frame — a broken encoder, a flashing scene,
/// a decoder producing noise — so the track script measures the half of the dashboard
/// that must *not* move at the same time as the half that must.
struct DashboardPane {
    struct Rect {
        int x = 0, y = 0, width = 0, height = 0;
    };
    /// The top half of the map pane: the floating controls sit in its bottom-left
    /// corner and the attribution in its bottom-right, and neither follows the vehicle.
    Rect map;
    /// The configurable tile row, which reads "not configured" for the whole session.
    Rect tiles;
    /// P7.2/P7.3 (the controls script): the centres of the zoom bubbles and the header
    /// icons, and the boxes whose pictures their presses change.
    QPoint zoomIn, zoomOut, pauseIcon, dayNightIcon;
    Rect header, pauseBox;
    /// P7.4/P7.5 (the endroute script): the favourites tile the trip panel takes over, the
    /// turn card across the top of the map, the media pane, End route's centre and the
    /// first player row's centre (laid out with the no-access hint, as in the lab).
    Rect tripTile, turnCard, media;
    QPoint endRoute, resumeRow;
    /// P8.2 (the voice script): the voice toggle at the right end of End route's row, its
    /// box for the picture check and its centre for the press.
    Rect voiceBox;
    QPoint voiceToggle;
    /// P7.8/P7.9 (the calls script): the flex tile, its first notification row, Answer's
    /// centre and a patch of its plate away from the word (to see it turn green), the call
    /// row's buttons with full controls (Mute, Keypad, Hang up) and Hang up alone, and the
    /// keypad: its box, echo strip, Close and the twelve keys in "123456789*0#" order.
    Rect flexTile, muteBox, keypad, keypadEcho;
    QPoint notificationRow, answer, answerPatch, callRow[3], hangUpAlone, keypadClose, keys[12];
    /// P9.6 (the traffic script): the whole map pane, and the turn card divided for a traffic
    /// offer in the layout the app was told to use (DashboardLayout.offerCard, offerPart,
    /// offerButton(0) Take and (1) Keep, offerView, trafficAttribution is not mirrored: the
    /// frames show it). A change to DashboardLayout's offer geometry not made here shows up as
    /// a Take or Keep press that hits nothing.
    Rect mapFull, offerCard, offerPart, offerTake, offerKeep, offerView;
    /// P10 (the weather script): DashboardLayout.headerWeather, the header's weather item, a
    /// margin left of the notification pause, full header height and 2.2 headers wide. A
    /// change there not made here shows up as a press on the item that opens nothing.
    Rect headerWeather;

    /// Mirrors DashboardLayout (Dashboard.kt): the pane, zoomBubble, headerBattery,
    /// headerSignal and headerIcon. A change there that is not made here shows up as a
    /// controls-script press that hits nothing.
    static DashboardPane of(const Layout& content, bool mapOnLeft, bool offerStacked = false)
    {
        const int margin = std::max(2, content.width / 96);
        const int header = std::max(24, content.height / 9);
        const int bodyY = content.y + header;
        const int bodyHeight = content.height - header;
        const int paneWidth = (content.width - 3 * margin) / 2;
        const int paneHeight = bodyHeight - 2 * margin;
        const int leftX = content.x + margin;
        const int rightX = content.x + 2 * margin + paneWidth;
        const int rowHeight = (paneHeight - margin) / 2;
        DashboardPane pane;
        pane.map = {mapOnLeft ? leftX : rightX, bodyY + margin, paneWidth, paneHeight / 2};
        pane.tiles = {mapOnLeft ? rightX : leftX, bodyY + margin + rowHeight + margin,
                      paneWidth, rowHeight};
        const int mapX = mapOnLeft ? leftX : rightX;
        const int mapBottom = bodyY + margin + paneHeight;
        const int bubble = std::max(24, std::min(paneWidth, paneHeight) / 8);
        const int creditTop = mapBottom - margin / 2 - std::max(10, paneHeight / 40);
        auto zoom = [&](int index) {
            const int bottom = creditTop - margin - (1 - index) * (bubble + margin);
            return QPoint(mapX + paneWidth - margin - bubble + bubble / 2, bottom - bubble + bubble / 2);
        };
        pane.zoomIn = zoom(0);
        pane.zoomOut = zoom(1);
        const int batteryX = content.x + content.width - margin - header * 13 / 10;
        const int signalX = batteryX - margin - header * 11 / 10;
        auto icon = [&](int index) {
            const int right = signalX - margin - (1 - index) * (header + margin);
            return Rect{right - header, content.y, header, header};
        };
        pane.pauseBox = icon(0);
        pane.pauseIcon = QPoint(pane.pauseBox.x + header / 2, content.y + header / 2);
        const Rect dayNight = icon(1);
        pane.dayNightIcon = QPoint(dayNight.x + header / 2, content.y + header / 2);
        // The header's text side, left of the icons, for the palette check.
        pane.header = {content.x, content.y, pane.pauseBox.x - margin - content.x, header};
        // P10: DashboardLayout.headerWeather.
        const int weatherWidth = header * 22 / 10;
        pane.headerWeather = {pane.pauseBox.x - margin - weatherWidth, content.y, weatherWidth, header};
        // DashboardLayout.media, tiles[0], mediaButton(0), endRoute, turnCard, playerRow.
        const int originX = mapOnLeft ? rightX : leftX;
        pane.media = {originX, bodyY + margin, paneWidth, rowHeight};
        const int tileWidth = (paneWidth - margin) / 2;
        pane.tripTile = {originX, bodyY + margin + rowHeight + margin, tileWidth, rowHeight};
        const int buttonTop = pane.media.y + pane.media.height / 2;
        const int buttonHeight = pane.media.y + pane.media.height - margin - buttonTop;
        const int endHeight = std::min(buttonHeight, std::max(0, rowHeight - 3 * margin));
        // P8.2: DashboardLayout.voiceToggle, then endRoute a margin short of it.
        const int voiceWidth = std::min(endHeight, std::max(0, (tileWidth - 3 * margin) / 3));
        const int endTop = pane.tripTile.y + rowHeight - margin - endHeight;
        pane.voiceBox = {pane.tripTile.x + tileWidth - margin - voiceWidth, endTop, voiceWidth, endHeight};
        pane.voiceToggle = QPoint(pane.voiceBox.x + voiceWidth / 2, endTop + endHeight / 2);
        const int endRight = voiceWidth > 0 ? pane.voiceBox.x - margin : pane.tripTile.x + tileWidth - margin;
        const int endWidth = std::max(0, endRight - pane.tripTile.x - margin);
        pane.endRoute = QPoint(pane.tripTile.x + margin + endWidth / 2, endTop + endHeight / 2);
        pane.turnCard = {mapX + margin, bodyY + margin + margin, paneWidth - 2 * margin, std::max(24, paneHeight / 5)};
        const int hintHeight = std::max(10, rowHeight / 10);
        const int hintY = pane.media.y + rowHeight - margin - hintHeight;
        const int rowTop = pane.media.y + margin;
        const int playerRow = (hintY - rowTop - margin) / 2;
        pane.resumeRow = QPoint(pane.media.x + paneWidth / 2, rowTop + playerRow / 2);
        // DashboardLayout.flexTile, notificationsList, callName, callDuration, ringButton,
        // callRowButton, keypad, keypadClose, keypadEcho, keypadKey.
        pane.flexTile = {originX + tileWidth + margin, pane.tripTile.y, tileWidth, rowHeight};
        const Rect& flex = pane.flexTile;
        const int title = rowHeight / 5;
        const int listTop = flex.y + title;
        const int listHeight = std::max(0, flex.y + rowHeight - margin - listTop);
        pane.notificationRow = QPoint(flex.x + flex.width / 2, listTop + listHeight / 2 / 2);
        const int nameBottom = flex.y + title + rowHeight / 5;
        const int ringTop = nameBottom + margin;
        const int ringWidth = std::max(0, (tileWidth - 3 * margin) / 2);
        const int ringHeight = std::max(0, flex.y + rowHeight - margin - ringTop);
        pane.answer = QPoint(flex.x + margin + ringWidth / 2, ringTop + ringHeight / 2);
        pane.answerPatch = QPoint(flex.x + margin + ringWidth / 6, ringTop + ringHeight / 6);
        const int rowTopCall = nameBottom + rowHeight / 9 + margin;
        const int rowCallHeight = std::max(0, flex.y + rowHeight - margin - rowTopCall);
        const int callWidth = std::max(0, (tileWidth - 4 * margin) / 3);
        for (int i = 0; i < 3; ++i)
            pane.callRow[i] = QPoint(flex.x + margin + i * (callWidth + margin) + callWidth / 2,
                                     rowTopCall + rowCallHeight / 2);
        pane.muteBox = {flex.x + margin, rowTopCall, callWidth, rowCallHeight};
        pane.hangUpAlone = QPoint(flex.x + flex.width / 2, rowTopCall + rowCallHeight / 2);
        pane.keypad = {originX, pane.media.y, paneWidth, rowHeight * 2 + margin};
        const Rect& pad = pane.keypad;
        const int strip = pad.height / 8;
        const int closeWidth = pad.width / 4;
        const int closeX = pad.x + pad.width - margin - closeWidth;
        pane.keypadClose = QPoint(closeX + closeWidth / 2, pad.y + margin + strip / 2);
        pane.keypadEcho = {pad.x + margin, pad.y + margin, std::max(0, closeX - margin - pad.x - margin), strip};
        const int keysTop = pad.y + margin + strip + margin;
        const int keyWidth = std::max(0, (pad.width - 4 * margin) / 3);
        const int keyHeight = std::max(0, (pad.y + pad.height - margin - keysTop - 3 * margin) / 4);
        for (int i = 0; i < 12; ++i)
            pane.keys[i] = QPoint(pad.x + margin + (i % 3) * (keyWidth + margin) + keyWidth / 2,
                                  keysTop + (i / 3) * (keyHeight + margin) + keyHeight / 2);
        // DashboardLayout.map, bubbleSize, turnCard, offerCard, offerTurn, offerPart,
        // offerButton and offerView (P9.6).
        pane.mapFull = {mapX, bodyY + margin, paneWidth, paneHeight};
        const Rect& full = pane.mapFull;
        const int mapBubble = std::max(24, std::min(full.width, full.height) / 8);
        const Rect card = {full.x + margin, full.y + margin, std::max(0, full.width - 2 * margin), std::max(24, full.height / 5)};
        const int tall = offerStacked ? card.height + std::max(mapBubble + 2 * margin, card.height * 3 / 4)
                                      : std::max(card.height, margin + card.height / 2 + mapBubble + margin);
        pane.offerCard = {card.x, card.y, card.width, tall};
        const Rect& oc = pane.offerCard;
        if (offerStacked) {
            pane.offerPart = {oc.x, card.y + card.height, oc.width, oc.y + oc.height - (card.y + card.height)};
            const Rect& part = pane.offerPart;
            const int h = std::max(0, part.height - 2 * margin);
            const int w = std::max(mapBubble * 3 / 2, part.width / 5);
            for (int i = 0; i < 2; ++i) {
                const int fromRight = 1 - i;
                const Rect b = {part.x + part.width - margin - w - fromRight * (w + margin), part.y + margin, w, h};
                (i == 0 ? pane.offerTake : pane.offerKeep) = b;
            }
        } else {
            const int turnWidth = oc.width * 2 / 5;
            pane.offerPart = {oc.x + turnWidth, oc.y, oc.width - turnWidth, oc.height};
            const Rect& part = pane.offerPart;
            const int w = std::max(0, (part.width - 3 * margin) / 2);
            for (int i = 0; i < 2; ++i) {
                const Rect b = {part.x + margin + i * (w + margin), part.y + part.height - margin - mapBubble, w, mapBubble};
                (i == 0 ? pane.offerTake : pane.offerKeep) = b;
            }
        }
        const int viewTop = oc.y + oc.height + margin;
        pane.offerView = {full.x + margin, viewTop, std::max(0, full.width - 2 * margin),
                          std::max(0, full.y + full.height - margin - viewTop)};
        return pane;
    }
};

/// One H.264 decoder for the received stream.
class Decoder {
public:
    bool open()
    {
        const AVCodec* codec = avcodec_find_decoder(AV_CODEC_ID_H264);
        if (!codec)
            return false;
        context_ = avcodec_alloc_context3(codec);
        if (!context_)
            return false;
        // Report broken bitstreams instead of concealing them.
        context_->err_recognition = AV_EF_EXPLODE | AV_EF_CAREFUL;
        context_->thread_count = 1;
        if (avcodec_open2(context_, codec, nullptr) < 0)
            return false;
        packet_ = av_packet_alloc();
        frame_ = av_frame_alloc();
        latest_ = av_frame_alloc();
        return packet_ && frame_ && latest_;
    }

    ~Decoder()
    {
        av_frame_free(&latest_);
        av_frame_free(&frame_);
        av_packet_free(&packet_);
        avcodec_free_context(&context_);
    }

    /// Whether an Annex B unit contains a coded slice; a codec configuration unit
    /// (SPS and PPS only) produces no picture and is not a decode error.
    static bool hasSlice(const QByteArray& unit)
    {
        const auto* data = reinterpret_cast<const uint8_t*>(unit.constData());
        for (int i = 0; i + 3 < unit.size(); ++i) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                const uint8_t type = data[i + 3] & 0x1f;
                if (type == 1 || type == 5)
                    return true;
            }
        }
        return false;
    }

    /// Decodes one access unit. Returns the number of pictures it produced.
    int decode(const QByteArray& unit)
    {
        const bool slices = hasSlice(unit);
        // The decoder needs padded input.
        buffer_.resize(unit.size() + AV_INPUT_BUFFER_PADDING_SIZE);
        std::memcpy(buffer_.data(), unit.constData(), unit.size());
        std::memset(buffer_.data() + unit.size(), 0, AV_INPUT_BUFFER_PADDING_SIZE);
        packet_->data = reinterpret_cast<uint8_t*>(buffer_.data());
        packet_->size = unit.size();
        int sent = avcodec_send_packet(context_, packet_);
        if (sent < 0) {
            if (slices)
                ++errors;
            else
                ++configs;
            return 0;
        }
        if (!slices)
            ++configs;
        int pictures = 0;
        while (true) {
            int got = avcodec_receive_frame(context_, frame_);
            if (got == AVERROR(EAGAIN) || got == AVERROR_EOF)
                break;
            if (got < 0) {
                ++errors;
                break;
            }
            ++pictures;
            ++frames;
            width = frame_->width;
            height = frame_->height;
            // The next receive call unrefs frame_, so keep this picture for sampling.
            av_frame_unref(latest_);
            av_frame_move_ref(latest_, frame_);
            haveFrame_ = true;
        }
        return pictures;
    }

    /// Luma and chroma of the latest decoded picture at (x, y).
    Yuv sample(int x, int y) const
    {
        if (!haveFrame_ || !latest_->data[0] || x < 0 || y < 0 || x >= width || y >= height)
            return Yuv{-1, -1, -1};
        return Yuv{latest_->data[0][y * latest_->linesize[0] + x],
                   latest_->data[1][(y / 2) * latest_->linesize[1] + x / 2],
                   latest_->data[2][(y / 2) * latest_->linesize[2] + x / 2]};
    }

    /// The luma plane over a rectangle of the latest picture, row-major.
    ///
    /// A single pixel says whether one thing changed colour; a rectangle says how much
    /// of a pane changed, which is the only way to tell a map that is panning under the
    /// vehicle from one that redrew a marker and nothing else. Luma alone: a map moving
    /// over its own tiles changes brightness everywhere it moves, and carrying the
    /// chroma planes would triple the copy for no extra evidence.
    QByteArray luma(int x, int y, int w, int h) const
    {
        if (!haveFrame_ || !latest_->data[0] || x < 0 || y < 0 || w <= 0 || h <= 0
            || x + w > width || y + h > height)
            return QByteArray();
        QByteArray out(w * h, 0);
        for (int row = 0; row < h; ++row) {
            std::memcpy(out.data() + static_cast<qsizetype>(row) * w,
                        latest_->data[0] + static_cast<size_t>(y + row) * latest_->linesize[0] + x,
                        static_cast<size_t>(w));
        }
        return out;
    }

    /// How many pixels of a rectangle of the latest picture [test] accepts, given each
    /// pixel's Y, U and V (P9: the traffic colours and the offer's Take button). -1 when the
    /// rectangle is not inside the picture.
    template <class Test>
    int count(int x, int y, int w, int h, Test test) const
    {
        if (!haveFrame_ || !latest_->data[0] || x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > width
            || y + h > height)
            return -1;
        int n = 0;
        for (int row = y; row < y + h; ++row) {
            const uint8_t* luma = latest_->data[0] + static_cast<size_t>(row) * latest_->linesize[0];
            const uint8_t* u = latest_->data[1] + static_cast<size_t>(row / 2) * latest_->linesize[1];
            const uint8_t* v = latest_->data[2] + static_cast<size_t>(row / 2) * latest_->linesize[2];
            for (int col = x; col < x + w; ++col) {
                if (test(luma[col], u[col / 2], v[col / 2]))
                    ++n;
            }
        }
        return n;
    }

    int frames = 0;
    int errors = 0;
    /// Codec configuration units (parameter sets without a picture).
    int configs = 0;
    int width = 0;
    int height = 0;

private:
    AVCodecContext* context_ = nullptr;
    AVPacket* packet_ = nullptr;
    AVFrame* frame_ = nullptr;
    /// The most recent decoded picture, kept for pixel checks.
    AVFrame* latest_ = nullptr;
    QByteArray buffer_;
    bool haveFrame_ = false;
};

/// Prodigy's video handler with an ack stall for the source's flow-control test, and a
/// paced mode for the congestion script: a head unit whose decoder is slower than the
/// stream.
class LabVideoHandler : public oaa::hu::VideoChannelHandler {
public:
    LabVideoHandler(uint8_t channelId, QObject* parent)
        : oaa::hu::VideoChannelHandler(channelId,
                                       oaa::proto::enums::VideoFocusMode::PROJECTED,
                                       parent)
    {
        connect(this, &oaa::hu::VideoChannelHandler::streamStarted, this,
                [this](int32_t session, uint32_t) { session_ = session; });
        clock_.start();
    }

    void onMediaData(const QByteArray& data, uint64_t timestamp) override
    {
        // Prodigy's AV callback loses the message ID. For our source, a unit
        // without a slice is SPS/PPS configuration: decode it without an ACK.
        // Timestamp zero alone cannot identify configuration (the first frame
        // may also have timestamp zero).
        if (!Decoder::hasSlice(data)) {
            emit videoFrameData(std::make_shared<const QByteArray>(data), 0);
            return;
        }
        if (stalling_) {
            // Decode without acknowledging: the source's window fills up.
            ++withheld_;
            emit videoFrameData(std::make_shared<const QByteArray>(data), 0);
            return;
        }
        if (paceKbps_ > 0 || paceFrameMs_ > 0) {
            // Shown at once, acknowledged when a serial decoder costing paceFrameMs_ a
            // frame plus a byte rate of paceKbps_ would have finished it: an IDR, the
            // largest frame, holds back everything behind it, as it would on a head unit
            // whose decoder cannot keep up.
            emit videoFrameData(std::make_shared<const QByteArray>(data), 0);
            const qint64 now = clock_.elapsed();
            const qint64 cost = paceFrameMs_
                + (paceKbps_ > 0 ? static_cast<qint64>(data.size()) * 8 / paceKbps_ : 0);
            busyUntil_ = std::max(busyUntil_, now) + cost;
            maxLagMs_ = std::max(maxLagMs_, busyUntil_ - now);
            ++paced_;
            const int32_t session = session_;
            QTimer::singleShot(static_cast<int>(busyUntil_ - now), this, [this, session]() {
                oaa::proto::messages::AVMediaAckIndication ack;
                ack.set_session_id(session);
                ack.set_ack_count(1);
                emit sendRequested(channelId(), oaa::AVMessageId::ACK_INDICATION, serialized(ack));
            });
            return;
        }
        oaa::hu::VideoChannelHandler::onMediaData(data, timestamp);
    }

    void setStalling(bool stalling) { stalling_ = stalling; }

    /// Paced acknowledgements (both zero: at once, as Prodigy does). Acks already
    /// scheduled still go out when they fall due.
    void setPace(int kbps, int frameMs)
    {
        paceKbps_ = kbps;
        paceFrameMs_ = frameMs;
    }

    /// Units acknowledged late, and the longest any waited.
    int paced() const { return paced_; }
    qint64 maxLagMs() const { return maxLagMs_; }

    /// Acknowledges everything withheld in one message, as a head unit that caught up.
    int releaseAcks()
    {
        const int count = withheld_;
        withheld_ = 0;
        if (count > 0) {
            oaa::proto::messages::AVMediaAckIndication ack;
            ack.set_session_id(session_);
            ack.set_ack_count(count);
            emit sendRequested(channelId(), oaa::AVMessageId::ACK_INDICATION, serialized(ack));
        }
        return count;
    }

private:
    bool stalling_ = false;
    int withheld_ = 0;
    int32_t session_ = 0;
    int paceKbps_ = 0;
    int paceFrameMs_ = 0;
    QElapsedTimer clock_;
    qint64 busyUntil_ = 0;
    qint64 maxLagMs_ = 0;
    int paced_ = 0;
};

/// The sphere the track's arithmetic moves a vehicle over. A drive of a few hundred
/// metres does not need a geoid, and a spherical step cannot be wrong about a longitude
/// the way a flat one can.
constexpr double EARTH_RADIUS_M = 6371000.0;

/// A synthetic drive for the sensor channel: a position that moves, with a speed and a
/// bearing that are the ones that position is actually moving at (P6.1).
///
/// The lab has only ever reported one fixed fix, so nothing in this source has had to
/// follow a moving map: steady-state redraw, a changing heading and a non-zero speed
/// have never been exercised at all, and three of the four silent defects P5.5's first
/// run caught were about change over time.
///
/// **Every value is integrated from the one before it** — the next position is the
/// previous one advanced along the current heading at the current speed. That is what
/// makes position, speed and bearing consistent by construction rather than by
/// agreement: a player reading three independent tables could publish a vehicle heading
/// north at 13 m/s whose coordinates went east, and nothing downstream would notice,
/// because nothing downstream has any other source to check it against.
/// The fixed fix and the block circuit's start (--position); the inland Massachusetts fix
/// the lab has always reported unless a run moves it (P11: Austin).
static double g_fixLatitude = 42.4242424;
static double g_fixLongitude = -71.2121212;

class TrackPlayer {
public:
    struct Leg {
        double heading; ///< degrees clockwise from north
        double metres;  ///< how far to run before the next corner
        double speed;   ///< m/s to settle at along it
    };

    TrackPlayer(double latitude, double longitude) : latitude_(latitude), longitude_(longitude)
    {
        heading_ = LEGS[0].heading;
        remaining_ = LEGS[0].metres;
    }

    /// A drive along given road geometry instead of the block circuit (P6.5): a vehicle
    /// that follows real streets, so a route computed from it can be followed and left.
    /// It pulls away, holds `cruise`, slows to a stop at the last point, and stays there.
    TrackPlayer(std::vector<std::pair<double, double>> path, double cruise)
        : latitude_(path.front().first), longitude_(path.front().second), path_(std::move(path)),
          cruise_(cruise)
    {
        heading_ = path_.size() > 1 ? bearing(path_[0], path_[1]) : 0.0;
        for (size_t i = 1; i < path_.size(); ++i)
            pathLength_ += distance(path_[i - 1], path_[i]);
    }

    /// Advances the vehicle by `seconds`. A parked one stays exactly where it is.
    void advance(double seconds)
    {
        if (!departed_)
            return;
        if (!path_.empty()) {
            advanceAlongPath(seconds);
            return;
        }
        const Leg& leg = LEGS[index_];
        // Corners are driven, not teleported. A head unit that jumps its heading by 90
        // degrees between two fixes is not something a road produces, and a source that
        // only re-rotated on a large change would pass against one that did.
        const double turn = TURN_RATE_DEG * seconds;
        heading_ += std::clamp(std::remainder(leg.heading - heading_, 360.0), -turn, turn);
        heading_ = std::fmod(heading_ + 360.0, 360.0);
        // Pulling away and slowing for a corner, rather than stepping between speeds:
        // the speed the source is shown has to be one the position could have produced.
        const double accel = ACCEL_MS2 * seconds;
        speed_ += std::clamp(leg.speed - speed_, -accel, accel);
        maxSpeed_ = std::max(maxSpeed_, speed_);

        const double step = speed_ * seconds;
        const double angular = step / EARTH_RADIUS_M;
        const double lat = latitude_ * M_PI / 180.0;
        const double lon = longitude_ * M_PI / 180.0;
        const double theta = heading_ * M_PI / 180.0;
        const double nextLat = std::asin(std::sin(lat) * std::cos(angular)
                                         + std::cos(lat) * std::sin(angular) * std::cos(theta));
        const double nextLon =
            lon
            + std::atan2(std::sin(theta) * std::sin(angular) * std::cos(lat),
                         std::cos(angular) - std::sin(lat) * std::sin(nextLat));
        latitude_ = nextLat * 180.0 / M_PI;
        longitude_ = std::fmod(nextLon * 180.0 / M_PI + 540.0, 360.0) - 180.0;

        travelled_ += step;
        remaining_ -= step;
        if (remaining_ <= 0.0) {
            index_ = (index_ + 1) % std::size(LEGS);
            remaining_ = LEGS[index_].metres;
            ++corners_;
        }
    }

    /// Pulls away from the kerb. Held until then so the script has a settled picture of
    /// a parked vehicle to measure the moving one against.
    void depart() { departed_ = true; }
    bool moving() const { return departed_; }
    /// A path drive has reached its last point and stopped. Never true for the circuit.
    bool finished() const { return !path_.empty() && segment_ + 1 >= path_.size() && speed_ == 0.0; }
    bool followsPath() const { return !path_.empty(); }
    double pathLength() const { return pathLength_; }

    double speed() const { return speed_; }
    double heading() const { return heading_; }
    double travelled() const { return travelled_; }
    double maxSpeed() const { return maxSpeed_; }
    int corners() const { return corners_; }

    // The scales this lab sends, and the reason each is what it is. None of them is
    // measured from a vehicle: Ford was stationary for the whole P5.2 harvest, so its
    // speed was 0, which is 0 at every scale, and its bearing (103553459) reads as 1e6
    // rather than the 1e3 the lab has always used. The lab deliberately keeps its own
    // scales unequal to Ford's so that a source which hardcoded either one fails here.
    /// Degrees at 1e7, which is Android Auto's convention for a position.
    int32_t wireLatitude() const { return scaled(latitude_, 1e7); }
    int32_t wireLongitude() const { return scaled(longitude_, 1e7); }
    /// Thousandths of a degree, matching the fixed fix's 1790 (1.79 degrees).
    int32_t wireBearing() const { return scaled(heading_, 1e3); }
    /// Millimetres per second. Unambiguous under the app's own candidate list, where a
    /// raw m/s value below 90 would also be in range and could not be told apart.
    int32_t wireSpeed() const { return scaled(speed_, 1e3); }

private:
    static int32_t scaled(double value, double factor)
    {
        return static_cast<int32_t>(std::llround(value * factor));
    }

    using Point = std::pair<double, double>;

    static double distance(const Point& a, const Point& b)
    {
        const double lat = (a.first + b.first) / 2.0 * M_PI / 180.0;
        const double x = (b.second - a.second) * M_PI / 180.0 * std::cos(lat) * EARTH_RADIUS_M;
        const double y = (b.first - a.first) * M_PI / 180.0 * EARTH_RADIUS_M;
        return std::hypot(x, y);
    }

    static double bearing(const Point& a, const Point& b)
    {
        const double lat = (a.first + b.first) / 2.0 * M_PI / 180.0;
        const double x = (b.second - a.second) * std::cos(lat);
        const double y = b.first - a.first;
        return std::fmod(std::atan2(x, y) * 180.0 / M_PI + 360.0, 360.0);
    }

    /// Moves along the path: the position is on the geometry at every tick, the heading is
    /// the direction of the segment it is on, and the speed is what the distance covered
    /// says it is — the same by-construction consistency the circuit has.
    void advanceAlongPath(double seconds)
    {
        const double left = pathLength_ - travelled_;
        // Slowing so as to stop at the last point rather than overshoot it.
        const double target = std::min(cruise_, std::sqrt(std::max(0.0, 2.0 * ACCEL_MS2 * left)));
        const double accel = ACCEL_MS2 * seconds;
        speed_ += std::clamp(target - speed_, -accel, accel);
        if (left <= 0.5) {
            // Arrived: at the last point exactly, rather than half a metre short of it
            // with nothing left to move by.
            speed_ = 0.0;
            travelled_ = pathLength_;
            segment_ = path_.size() - 1;
            latitude_ = path_.back().first;
            longitude_ = path_.back().second;
            return;
        }
        maxSpeed_ = std::max(maxSpeed_, speed_);
        double step = std::min(speed_ * seconds, left);
        travelled_ += step;
        while (step > 0.0 && segment_ + 1 < path_.size()) {
            const Point& a = path_[segment_];
            const Point& b = path_[segment_ + 1];
            const double length = distance(a, b);
            const double remaining = length - offset_;
            if (step < remaining) {
                offset_ += step;
                step = 0.0;
            } else {
                step -= remaining;
                offset_ = 0.0;
                ++segment_;
                ++corners_;
            }
        }
        if (segment_ + 1 >= path_.size()) {
            latitude_ = path_.back().first;
            longitude_ = path_.back().second;
            speed_ = 0.0;
            return;
        }
        const Point& a = path_[segment_];
        const Point& b = path_[segment_ + 1];
        const double length = distance(a, b);
        const double t = length > 0.0 ? offset_ / length : 0.0;
        latitude_ = a.first + t * (b.first - a.first);
        longitude_ = a.second + t * (b.second - a.second);
        if (length > 0.0)
            heading_ = bearing(a, b);
    }

    // Roughly a block circuit around the position the lab has always reported, at
    // suburban speeds, repeating so a longer run keeps driving instead of stopping.
    // Four cardinal headings with driven corners between them means the bearing sweeps
    // the whole circle, including the wrap through north, over one lap.
    static constexpr Leg LEGS[] = {
        {90.0, 200.0, 13.4},  // east, 48 km/h
        {0.0, 150.0, 9.0},    // north, 32 km/h
        {270.0, 200.0, 13.4}, // west
        {180.0, 150.0, 9.0},  // south
    };
    /// Degrees per second through a corner: about three seconds for a right angle.
    static constexpr double TURN_RATE_DEG = 30.0;
    /// Metres per second per second, pulling away and slowing down. Comfortable rather
    /// than brisk; a harder number would only make the speed less plausible.
    static constexpr double ACCEL_MS2 = 2.0;

    double latitude_;
    double longitude_;
    double heading_ = 0.0;
    double speed_ = 0.0;
    double maxSpeed_ = 0.0;
    double travelled_ = 0.0;
    double remaining_ = 0.0;
    size_t index_ = 0;
    int corners_ = 0;
    bool departed_ = false;
    std::vector<Point> path_;
    double cruise_ = 0.0;
    double pathLength_ = 0.0;
    size_t segment_ = 0;
    double offset_ = 0.0;
};

// Ford SYNC 4.0's nine advertised sensor types (report 617af5ce), so the lab asks the
// same questions the vehicle will answer. FUEL_LEVEL and GEAR are accepted and then
// never sent: "advertised, accepted, and silent" is the case P5.2 has to tell apart from
// populated, and Prodigy's own SensorChannelHandler cannot produce it.
const int LAB_SENSOR_TYPES[] = {1, 3, 6, 7, 8, 10, 13, 21, 24};

/// Sensor channel that answers a subscription for any type and pushes values for the
/// types it models: one fixed reading each, or — given a [TrackPlayer] — a vehicle that
/// sits at the kerb and then drives away (P6.1).
///
/// Prodigy's SensorChannelHandler covers night mode, driving status and parking brake
/// only, so the wider harvest needs this; the protobuf classes are still Prodigy's, so
/// the encode stays independent of the source's generated Rust types.
class LabSensorHandler : public oaa::IChannelHandler {
public:
    /// `night` is what NIGHT_DATA says when the source subscribes to it; a positive
    /// `flipMs` sends the opposite that long after the subscription, once (P7.6).
    LabSensorHandler(TrackPlayer* track, int intervalMs, bool night, int flipMs, QObject* parent)
        : oaa::IChannelHandler(parent), track_(track), intervalMs_(intervalMs), night_(night),
          flipMs_(flipMs)
    {
        timer_.setInterval(intervalMs_);
        QObject::connect(&timer_, &QTimer::timeout, this, [this]() { play(); });
    }

    uint8_t channelId() const override { return oaa::ChannelId::Sensor; }
    void onChannelOpened() override { open_ = true; }
    void onChannelClosed() override
    {
        open_ = false;
        timer_.stop();
    }

    void onMessage(uint16_t messageId, const QByteArray& payload, int dataOffset) override
    {
        const QByteArray data = QByteArray::fromRawData(payload.constData() + dataOffset,
                                                        payload.size() - dataOffset);
        if (messageId != oaa::SensorMessageId::SENSOR_START_REQUEST) {
            emit unknownMessage(messageId, data);
            return;
        }
        oaa::proto::messages::SensorStartRequestMessage request;
        if (!request.ParseFromArray(data.constData(), data.size()))
            return;
        const int type = static_cast<int>(request.sensor_type());
        requested.append(type);

        oaa::proto::messages::SensorStartResponseMessage response;
        response.set_status(oaa::proto::enums::Status::OK);
        emit sendRequested(channelId(), oaa::SensorMessageId::SENSOR_START_RESPONSE,
                           serialized(response));

        oaa::proto::messages::SensorEventIndication event;
        switch (type) {
        case 1:
            fillLocation(event.add_gps_location());
            // The track reports from its parked position until the script releases it,
            // so a run starts from the same picture the fixed fix produced.
            if (track_)
                timer_.start();
            break;
        case 3:
            fillSpeed(event.add_speed());
            break;
        case 7:
            event.add_parking_brake()->set_parking_brake(true);
            break;
        case 10:
            event.add_night_mode()->set_is_night(night_);
            ++nightEvents;
            if (flipMs_ > 0)
                QTimer::singleShot(flipMs_, this, [this]() { sendNight(!night_); });
            break;
        case 13:
            event.add_driving_status()->set_status(0);
            break;
        case 21:
            fillSatellites(event.add_gps_satellite());
            break;
        case 24:
            event.add_trailer();
            break;
        default:
            return; // advertised, accepted, and silent
        }
        populated.append(type);
        emit sendRequested(channelId(), oaa::SensorMessageId::SENSOR_EVENT_INDICATION,
                           serialized(event));
    }

    /// A NIGHT_DATA event on its own, as a head unit sends one when its light sensor or
    /// its headlamp switch changes (P7.6). Nothing if the source never subscribed:
    /// an event for a sensor nobody asked for is not what a head unit does.
    bool sendNight(bool night)
    {
        night_ = night;
        if (!open_ || !requested.contains(10))
            return false;
        oaa::proto::messages::SensorEventIndication event;
        event.add_night_mode()->set_is_night(night);
        ++nightEvents;
        emit sendRequested(channelId(), oaa::SensorMessageId::SENSOR_EVENT_INDICATION,
                           serialized(event));
        return true;
    }

    bool night() const { return night_; }

    QList<int> requested;
    QList<int> populated;
    /// NIGHT_DATA events sent, the subscription's answer included.
    int nightEvents = 0;
    /// Position events played by the track, including the parked ones.
    int fixes = 0;
    /// Events carrying speed and satellites but no position.
    int slowEvents = 0;

private:
    /// One position, in the scales documented on [TrackPlayer], or the fixed inland fix
    /// the lab reported before P6.1. The source must redact either by default, and the
    /// check at the end of a run is that no report ever repeats them.
    void fillLocation(oaa::proto::data::GPSLocation* fix) const
    {
        if (track_) {
            fix->set_latitude(track_->wireLatitude());
            fix->set_longitude(track_->wireLongitude());
            fix->set_speed(track_->wireSpeed());
            fix->set_bearing(track_->wireBearing());
        } else {
            fix->set_latitude(static_cast<int32_t>(std::llround(g_fixLatitude * 1e7)));
            fix->set_longitude(static_cast<int32_t>(std::llround(g_fixLongitude * 1e7)));
            fix->set_speed(0);
            fix->set_bearing(1790);
        }
        fix->set_accuracy(8);
        fix->set_altitude(213);
    }

    void fillSpeed(oaa::proto::data::Speed* speed) const
    {
        speed->set_speed(track_ ? track_->wireSpeed() : 0);
        speed->set_cruise_engaged(false);
    }

    static void fillSatellites(oaa::proto::data::GpsSatelliteData* gps)
    {
        gps->set_satellite_count(11);
        gps->set_satellites_used(7);
    }

    /// One tick of the track: a position, and every fifth time also the speed and
    /// satellite counts.
    ///
    /// The two rates are the ones the vehicle itself used. The G6/G7 runs recorded 208
    /// LOCATION events against 42 CAR_SPEED and 42 GPS in the same session — five to
    /// one — so a lab that pushed all three together would never produce the message
    /// that matters most here: one carrying no position at all, which the source has to
    /// merge into the fix it already holds rather than treat as a vehicle at 0,0.
    void play()
    {
        if (!open_ || !track_)
            return;
        track_->advance(intervalMs_ / 1000.0);
        if (requested.contains(1)) {
            oaa::proto::messages::SensorEventIndication event;
            fillLocation(event.add_gps_location());
            ++fixes;
            emit sendRequested(channelId(), oaa::SensorMessageId::SENSOR_EVENT_INDICATION,
                               serialized(event));
        }
        if (++tick_ % SLOW_EVERY != 0)
            return;
        oaa::proto::messages::SensorEventIndication event;
        bool any = false;
        if (requested.contains(3)) {
            fillSpeed(event.add_speed());
            any = true;
        }
        if (requested.contains(21)) {
            fillSatellites(event.add_gps_satellite());
            any = true;
        }
        if (!any)
            return;
        ++slowEvents;
        emit sendRequested(channelId(), oaa::SensorMessageId::SENSOR_EVENT_INDICATION,
                           serialized(event));
    }

    /// Position events per speed event, from the vehicle's measured 208:42.
    static constexpr int SLOW_EVERY = 5;

    TrackPlayer* track_ = nullptr;
    int intervalMs_ = 200;
    bool night_ = true;
    int flipMs_ = 0;
    int tick_ = 0;
    QTimer timer_;
    bool open_ = false;
};

/// The fundamentals the source's own audio carries: 440 Hz left, 880 Hz right. Both
/// sides of this check are written from the same two numbers, so a swapped pair or a
/// wrong sample rate shows up as a failed check rather than as silence nobody notices.
constexpr double LAB_TONE_LEFT_HZ = 440.0;
constexpr double LAB_TONE_RIGHT_HZ = 880.0;

/// Goertzel magnitude of `hz` in one channel of an interleaved S16LE buffer, normalised
/// so a full-scale sine reads 1.0. Cheaper and clearer here than an FFT: the two
/// frequencies to test are known in advance.
double toneMagnitude(const QByteArray& pcm, int channels, int channel, int sampleRate,
                     double hz)
{
    const int frames = pcm.size() / (2 * channels);
    if (frames <= 0 || sampleRate <= 0)
        return 0.0;
    const double w = 2.0 * M_PI * hz / sampleRate;
    const double coeff = 2.0 * std::cos(w);
    double s1 = 0.0, s2 = 0.0;
    const auto* samples = reinterpret_cast<const qint16*>(pcm.constData());
    for (int frame = 0; frame < frames; ++frame) {
        const double x = samples[frame * channels + channel] / 32768.0;
        const double s0 = x + coeff * s1 - s2;
        s2 = s1;
        s1 = s0;
    }
    const double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
    return 2.0 * std::sqrt(std::max(0.0, power)) / frames;
}

/// Prodigy's navigation handler, plus the one thing it does not do: announce a cluster.
///
/// `NavigationChannelHandler` decodes the state, the maneuver and the distance from the
/// pinned Prodigy library's own generated schemas — a second reader that shares no code
/// with the source, which is the whole point of checking here rather than only in
/// `hu-sim` (spec §8.1). What it never sends is `InstrumentClusterStart`, so without this
/// subclass the lab can only ever exercise the head unit that has no cluster behind the
/// service it advertises. That case is worth having too, and `--navigation-cluster=none`
/// is it.
///
/// Gaps are measured on this side's own clock. A source that ignored the advertised
/// `minimum_interval_ms` cannot be caught by reading its own report, which would say the
/// interval it believes it used.
class LabNavigationHandler : public oaa::hu::NavigationChannelHandler {
public:
    LabNavigationHandler(bool announceCluster, QObject* parent)
        : oaa::hu::NavigationChannelHandler(parent), announce_(announceCluster)
    {
    }

    void onChannelOpened() override
    {
        oaa::hu::NavigationChannelHandler::onChannelOpened();
        if (!announce_)
            return;
        // Empty message: the id is the whole of it.
        emit sendRequested(channelId(), 0x8001, QByteArray());
        clusterAnnounced = true;
    }

    void onMessage(uint16_t messageId, const QByteArray& payload, int dataOffset) override
    {
        switch (messageId) {
        case oaa::NavigationMessageId::NAV_STATE:
            ++states;
            break;
        case oaa::NavigationMessageId::NAV_STEP:
            ++notifications;
            timeUpdate();
            break;
        case oaa::NavigationMessageId::NAV_DISTANCE:
            ++distances;
            timeUpdate();
            break;
        default:
            ++unexpected;
            break;
        }
        oaa::hu::NavigationChannelHandler::onMessage(messageId, payload, dataOffset);
    }

    bool announceCluster() const { return announce_; }

    bool clusterAnnounced = false;
    int states = 0;
    int notifications = 0;
    int distances = 0;
    int unexpected = 0;
    /// Smallest gap between two updates, in milliseconds; -1 until there are two.
    qint64 smallestGapMs = -1;
    int updates = 0;

private:
    /// One update is a maneuver and a distance about the same instant, arriving back to
    /// back, so anything within a tenth of a second of the last message is the same
    /// update rather than a new one.
    void timeUpdate()
    {
        if (!clock_.isValid()) {
            clock_.start();
            ++updates;
            return;
        }
        const qint64 gap = clock_.elapsed();
        if (gap < 100)
            return;
        clock_.restart();
        ++updates;
        if (smallestGapMs < 0 || gap < smallestGapMs)
            smallestGapMs = gap;
    }

    bool announce_ = true;
    QElapsedTimer clock_;
};

/// Prodigy's audio handler with the underflow injection the P5.3 plan asks for, plus a
/// record of what arrived. Prodigy answers the setup, the start indication and every
/// buffer itself; the only thing missing is 0x800B, which no open-source head unit sends
/// and which is the most valuable diagnostic in the audio path.
class LabAudioHandler : public oaa::hu::AudioChannelHandler {
public:
    LabAudioHandler(uint8_t channelId, QObject* parent)
        : oaa::hu::AudioChannelHandler(channelId, parent)
    {
    }

    void onMediaData(const QByteArray& data, uint64_t timestamp) override
    {
        ++buffers;
        bytes += data.size();
        if (largest < data.size())
            largest = data.size();
        latest = data;
        oaa::hu::AudioChannelHandler::onMediaData(data, timestamp);
        if (underflowAfter > 0 && buffers == underflowAfter) {
            // Never answered by the source, and sent with no payload: modern Android Auto
            // parses none for this message.
            emit sendRequested(channelId(), oaa::AVMessageId::AUDIO_UNDERFLOW, QByteArray());
            ++underflowsSent;
        }
    }

    int underflowAfter = 0;
    int underflowsSent = 0;
    int buffers = 0;
    qint64 bytes = 0;
    int largest = 0;
    /// The most recent buffer, for the tone check.
    QByteArray latest;
};

struct Capabilities {
    uint8_t video = oaa::ChannelId::Video;
    uint8_t input = oaa::ChannelId::Input;
    int width = 800;
    int height = 480;
    int fps = 2; // VideoFPS::_30
    int marginWidth = 0;
    int marginHeight = 0;
    int touchWidth = 800;
    int touchHeight = 480;
    bool sensors = false;
    /// Advertise Ford's nine types and drive them from LabSensorHandler (P5.2) instead
    /// of the restriction pair Prodigy's own handler covers.
    bool sensorsAll = false;
    /// Play a moving synthetic track into the sensor channel rather than one fixed
    /// position (P6.1). Needs the full sensor set: the track is LOCATION.
    bool track = false;
    /// Position rate. 200 ms is the vehicle's own measured LOCATION cadence (208 events
    /// in a 40.8 s session), not a number chosen for the lab's convenience.
    int trackIntervalMs = 200;
    /// Which half of the dashboard the owned map occupies, mirroring the app's own
    /// setting, which defaults to the left.
    bool mapOnLeft = true;
    /// What NIGHT_DATA says when the source subscribes (P7.6). True by default: it is
    /// what every lab run before P7.6 sent, and what the Ford sent with its display
    /// pinned to dark, so existing scripts see exactly the picture they always did.
    bool night = true;
    /// With --night flip:<seconds>, one flip to the opposite that long after the
    /// subscription; 0 for none.
    int nightFlipMs = 0;
    /// Advertise the now-playing channel. Prodigy's MediaStatusChannelHandler hardcodes
    /// ChannelId::MediaStatus (10), so the descriptor has to use that id.
    bool mediaInfo = false;
    /// Advertise the instrument cluster channel (P6.2). Prodigy's
    /// NavigationChannelHandler hardcodes ChannelId::Navigation (9), so the descriptor
    /// has to use that id — which is deliberately not Ford's 11 or hu-sim's, so a source
    /// that hardcoded either fails here.
    bool navigation = false;
    /// Whether the receiver announces a cluster at all. Prodigy never does on its own;
    /// a head unit that advertises the service and has no cluster behind it is a real
    /// shape and the one the vehicle may turn out to have.
    bool navigationCluster = true;
    /// Advertised minimum_interval_ms. 1500 is slower than the source's own default, so
    /// the head unit's floor is the one that binds and the gap below can check it.
    int navigationIntervalMs = 1500;
    /// Whether a maneuver sequence is expected at all. It follows from
    /// [`navigationCluster`] — a source that waits for a cluster sends nothing to a head
    /// unit that never announces one — and is overridable, because a source told to send
    /// regardless is the other half of that experiment.
    bool navigationExpectSequence = true;
    /// Advertise one PCM MEDIA audio channel on ChannelId::MediaAudio (4), at the
    /// conventional 48000/16/2 (P5.3).
    bool audio = false;
    int audioSampleRate = 48000;
    int audioChannels = 2;
    int configs = 1;
    // Space to report touches in; mirrors aap_session::input::TouchSpace. Ford SYNC 4.0
    // sends frame pixels whatever touch screen it advertises, counted from the content
    // rectangle's origin, so that is the default here too (docs/references.md, "Touch
    // coordinate space"). With no margins it is the same as "frame".
    QString touchSpace = "content";

    Layout layout() const
    {
        return Layout{marginWidth / 2, marginHeight / 2, width - marginWidth,
                      height - marginHeight};
    }
    // A frame pixel as this head unit would report it (spec §8.3).
    QPoint toTouch(QPoint frame) const
    {
        const Layout l = layout();
        if (touchSpace == "content")
            return QPoint(frame.x() - l.x, frame.y() - l.y);
        if (touchSpace == "advertised")
            return QPoint((frame.x() - l.x) * touchWidth / l.width,
                          (frame.y() - l.y) * touchHeight / l.height);
        return frame;
    }
};

void addChannels(oaa::SessionConfig& config, const Capabilities& caps)
{
    oaa::proto::data::ChannelDescriptor video;
    video.set_channel_id(caps.video);
    auto* av = video.mutable_av_channel();
    av->set_stream_type(oaa::proto::enums::MediaCodecType::MEDIA_CODEC_VIDEO_H264_BP);
    av->set_display_id(0);
    auto* videoConfig = av->add_video_configs();
    videoConfig->set_video_resolution(
        caps.width == 1920 ? oaa::proto::enums::VideoResolution::VIDEO_1920x1080
                           : caps.width == 1280
                                 ? oaa::proto::enums::VideoResolution::VIDEO_1280x720
                                 : oaa::proto::enums::VideoResolution::VIDEO_800x480);
    videoConfig->set_video_fps(caps.fps == 1 ? oaa::proto::enums::VideoFPS::_60
                                             : oaa::proto::enums::VideoFPS::_30);
    videoConfig->set_margin_width(caps.marginWidth);
    videoConfig->set_margin_height(caps.marginHeight);
    videoConfig->set_dpi(160);
    if (caps.marginWidth > 0 || caps.marginHeight > 0) {
        // Ford SYNC 4 sends insets alongside the margins; mirror that shape.
        auto* insets = videoConfig->mutable_additional_config()->mutable_display_insets();
        insets->set_left(caps.marginWidth / 2);
        insets->set_right(caps.marginWidth - caps.marginWidth / 2);
        insets->set_top(caps.marginHeight / 2);
        insets->set_bottom(caps.marginHeight - caps.marginHeight / 2);
    }
    config.channels.append({caps.video, serialized(video)});

    oaa::proto::data::ChannelDescriptor input;
    input.set_channel_id(caps.input);
    auto* inputConfig = input.mutable_input_channel();
    auto* touch = inputConfig->add_touch_screen_configs();
    touch->set_width(caps.touchWidth);
    touch->set_height(caps.touchHeight);
    inputConfig->add_supported_keycodes(84);
    // The transport keycodes Ford SYNC 4.0 binds, so --script transport can press them:
    // PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS.
    inputConfig->add_supported_keycodes(85);
    inputConfig->add_supported_keycodes(87);
    inputConfig->add_supported_keycodes(88);
    config.channels.append({caps.input, serialized(input)});

    if (caps.mediaInfo) {
        // MediaInfoChannel is an empty message: advertising the service is the whole of
        // the descriptor, and there is nothing for the source to negotiate.
        oaa::proto::data::ChannelDescriptor media;
        media.set_channel_id(oaa::ChannelId::MediaStatus);
        media.mutable_media_info_channel();
        config.channels.append({oaa::ChannelId::MediaStatus, serialized(media)});
    }

    if (caps.navigation) {
        // Unlike media_info this descriptor is configuration, not a marker: how often the
        // head unit will accept an update, and which presentation it wants. No image
        // options — Ford sends none either, and this source sends no images (P6 plan §2).
        oaa::proto::data::ChannelDescriptor navigation;
        navigation.set_channel_id(oaa::ChannelId::Navigation);
        auto* nav = navigation.mutable_navigation_channel();
        nav->set_minimum_interval_ms(caps.navigationIntervalMs);
        nav->set_type(oaa::proto::enums::NavigationType::TURN_BY_TURN);
        config.channels.append({oaa::ChannelId::Navigation, serialized(navigation)});
    }

    if (caps.audio) {
        // One audio channel, PCM carrying MEDIA: the slice deliberately starts with the
        // one configuration that needs no encoder on the source side.
        oaa::proto::data::ChannelDescriptor audio;
        audio.set_channel_id(oaa::ChannelId::MediaAudio);
        auto* audioAv = audio.mutable_av_channel();
        audioAv->set_stream_type(oaa::proto::enums::MediaCodecType::MEDIA_CODEC_AUDIO_PCM);
        audioAv->set_audio_type(oaa::proto::enums::AudioType::MEDIA);
        auto* audioConfig = audioAv->add_audio_configs();
        audioConfig->set_sample_rate(caps.audioSampleRate);
        audioConfig->set_bit_depth(16);
        audioConfig->set_channel_count(caps.audioChannels);
        config.channels.append({oaa::ChannelId::MediaAudio, serialized(audio)});
    }

    if (caps.sensors) {
        oaa::proto::data::ChannelDescriptor sensor;
        sensor.set_channel_id(oaa::ChannelId::Sensor);
        auto* sensorConfig = sensor.mutable_sensor_channel();
        if (caps.sensorsAll) {
            for (int type : LAB_SENSOR_TYPES)
                sensorConfig->add_sensors()->set_sensor_type(
                    static_cast<oaa::proto::enums::SensorType_Enum>(type));
        } else {
            sensorConfig->add_sensors()->set_sensor_type(
                oaa::proto::enums::SensorType::DRIVING_STATUS);
            sensorConfig->add_sensors()->set_sensor_type(
                oaa::proto::enums::SensorType::NIGHT_DATA);
        }
        config.channels.append({oaa::ChannelId::Sensor, serialized(sensor)});
    }
}

} // namespace

int main(int argc, char** argv)
{
    QCoreApplication app(argc, argv);
    QCommandLineParser parser;
    parser.setApplicationDescription("OpenAutoAndroid controlled lab receiver (Prodigy protocol library)");
    parser.addHelpOption();
    QCommandLineOption listenOption("listen", "Address and port to accept one source on.", "host:port", "127.0.0.1:5288");
    QCommandLineOption connectOption("connect", "Connect to this address instead of listening.", "host:port");
    QCommandLineOption versionOption("request-version", "Protocol version the receiver requests.", "major.minor", "1.1");
    QCommandLineOption traceOption("trace", "Receiver-side message trace (JSONL).", "path", "lab-receiver-trace.jsonl");
    QCommandLineOption timeoutOption("timeout-ms", "Give up after this long.", "ms", "30000");
    QCommandLineOption projectionOption("projection", "Register video, input, and sensor handlers and run the projection script.");
    QCommandLineOption sensorsOption("sensors",
                                     "Advertise a sensor channel: restriction (driving status and "
                                     "night mode, through Prodigy's own handler) or all (Ford's "
                                     "nine types, two of them accepted and then silent).",
                                     "set", "restriction");
    QCommandLineOption mediaInfoOption("media-info", "Advertise the now-playing channel and decode what the source publishes on it.");
    QCommandLineOption audioOption("audio",
                                   "Advertise one PCM MEDIA audio channel, grant audio focus, and "
                                   "measure the received PCM against the source's own tone.");
    QCommandLineOption navigationOption("navigation",
                                        "Advertise the instrument cluster channel, announce a "
                                        "cluster, grant navigation focus, and decode the maneuver "
                                        "sequence the source sends (P6.2).");
    QCommandLineOption navigationClusterOption("navigation-cluster",
                                               "Whether the receiver announces a cluster: start or "
                                               "none. `none` is what Prodigy does unaided — a head "
                                               "unit that advertises the service and never says a "
                                               "cluster is there.", "mode", "start");
    QCommandLineOption navigationIntervalOption("navigation-interval-ms",
                                                "Advertised minimum_interval_ms; the floor the "
                                                "source must not send faster than.", "ms", "1500");
    QCommandLineOption navigationExpectOption("navigation-expect",
                                              "What the cluster channel should carry: sequence, "
                                              "silence, or auto (sequence when a cluster is "
                                              "announced, silence when it is not).",
                                              "what", "auto");
    QCommandLineOption underflowOption("underflow-after",
                                       "Send 0x800B AUDIO_UNDERFLOW after this many audio buffers "
                                       "(0 never). No open-source head unit sends it.", "n", "0");
    // The vehicle projects 1920x1080; the lab defaulting to 800x480 makes anything
    // that depends on pixel area - label density on the owned map above all - behave
    // quite differently here than on the head unit (P5.5).
    QCommandLineOption videoConfigOption("video-config",
                                         "Advertised video configuration: 800x480, 1280x720 or 1920x1080.",
                                         "WxH", "800x480");
    QCommandLineOption marginsOption("margins", "Advertised video margins WxH (a Ford-like layout uses 74x0).", "WxH", "0x0");
    QCommandLineOption touchOption("touch", "Advertised touch screen size WxH.", "WxH", "");
    QCommandLineOption touchSpaceOption("touch-space",
                                        "Space to report touches in: content (what Ford SYNC 4.0 "
                                        "sends), frame, or advertised.", "name", "content");
    QCommandLineOption scriptOption("script",
                                    "Projection script: taps, focus, stall, transport, audio, "
                                    "track, navigation, guidance, daynight, controls, endroute, calls, speech, voice, traffic, "
                                    "weather, congestion, or none.", "name", "taps");
    QCommandLineOption trafficModeOption("traffic-mode",
                                         "For --script traffic (P9): offer (wait for the divided turn card, "
                                         "flip the palette and back for the renders, press Keep, wait for a "
                                         "second offer, press Take), closure (parked; watch the cluster "
                                         "re-route round a closure and the closure's colour in the map), "
                                         "off (drive --track-file; the source must fetch nothing), or render "
                                         "(offer up to Keep: the divided card in both palettes, for the "
                                         "renders), or drive (wait 30 s for traffic to arrive, then drive "
                                         "--track-file through it: the colours and the frame cost on the "
                                         "move).", "mode", "offer");
    QCommandLineOption offerLayoutOption("offer-layout",
                                         "How the app divides the turn card for an offer: side-by-side (its "
                                         "default) or stacked, as the lab told it.", "layout", "side-by-side");
    QCommandLineOption etaOutOption("eta-out",
                                    "Write every change of the cluster's time to the destination here, one "
                                    "line of epoch milliseconds, seconds and cluster states (P9.4).", "path");
    QCommandLineOption weatherModeOption("weather-mode",
                                         "For --script weather (P10): show (press the header's weather item: "
                                         "the view in both palettes, then a second press closes it), tap (the "
                                         "second press, a press on the view and the 30 s timeout each close it; "
                                         "then a call rings and a press is ignored), alert (a notification, "
                                         "then the lab's alert takes the tile; two presses dismiss two alerts), "
                                         "stale or unsupported (after the lab's read: the view in both palettes), "
                                         "off (no weather item, and a press on its place opens nothing) or "
                                         "notifier (the header's temperature after the lab's notifier).",
                                         "mode", "show");
    QCommandLineOption weatherDirOption("weather-dir",
                                        "For --script weather: where the receiver writes weather-receiver.txt "
                                        "(what it has seen, for the lab) and reads weather-lab.txt (what the lab "
                                        "has done, with epoch milliseconds).", "dir", "");
    QCommandLineOption callControlsOption("call-controls",
                                          "For --script calls: full (the Telecom route: Mute, Keypad and "
                                          "Hang up after Answer) or basic (the call-notification route: "
                                          "Hang up alone).", "which", "basic");
    QCommandLineOption videoOutOption("video-out", "Write the received Annex B stream here.", "path");
    QCommandLineOption holdOption("hold-ms", "Keep the session after the script.", "ms", "1000");
    QCommandLineOption toleranceOption("tolerance",
                                       "Allowed difference per color component in decoded frames.", "n", "20");
    QCommandLineOption audioOutOption("audio-out", "Write the received PCM here.", "path");
    QCommandLineOption trackOption("track",
                                   "Play a moving synthetic track into the sensor channel instead "
                                   "of one fixed position (P6.1). Implied by --script track.");
    QCommandLineOption trackIntervalOption("track-interval-ms",
                                           "Position rate of the track; the vehicle's own measured "
                                           "cadence is 200.", "ms", "200");
    QCommandLineOption trackFileOption("track-file",
                                       "Drive along this road geometry, one lat,lon per line "
                                       "(# comments allowed), instead of the block circuit "
                                       "(P6.5). Implies --track.", "path");
    QCommandLineOption trackSpeedOption("track-speed",
                                        "Cruise speed along --track-file, m/s.", "ms", "15");
    QCommandLineOption nightOption("night",
                                   "What NIGHT_DATA says (P7.6): night (the default, as before), "
                                   "day, or flip:<seconds> - day, then night that long after the "
                                   "source subscribes. Needs --sensors all.", "mode", "night");
    QCommandLineOption positionOption("position",
                                      "The fixed fix and the track's start, lat,lon (P11: 30.2672,-97.7431 "
                                      "for the Austin data).", "lat,lon", "42.4242424,-71.2121212");
    QCommandLineOption poiOption("poi",
                                 "For --script track (P11.5): the POI buttons the lab lit, gas,food,shop. "
                                 "The parked map must show each kind's markers, counted by colour, in "
                                 "both palettes (a render of each) before the vehicle departs.", "kinds", "");
    QCommandLineOption poiAheadOption("poi-ahead",
                                      "For --script track (P11.5a): once /out/poi-ahead-go exists, press this "
                                      "POI button (gas, food or shop) under the turn card, wait for the "
                                      "search-ahead card in the flex tile and press Add stop on it.", "kind", "");
    QCommandLineOption congestionKbpsOption("congestion-kbps",
                                            "For --script congestion: the slow phase's decoder, in kilobits of "
                                            "video a second; each unit is acknowledged when a serial decoder at "
                                            "this rate would have finished it.", "kbps", "1500");
    QCommandLineOption congestionFrameMsOption("congestion-frame-ms",
                                               "For --script congestion: the slow phase's fixed cost per frame.",
                                               "ms", "4");
    QCommandLineOption congestionSlowOption("congestion-slow-ms", "For --script congestion: how long the head "
                                            "unit is slow.", "ms", "45000");
    QCommandLineOption congestionNormalOption("congestion-normal-ms", "For --script congestion: how long it "
                                              "then keeps up before the checks.", "ms", "20000");
    QCommandLineOption congestionExpectOption("congestion-expect",
                                              "For --script congestion: whether the source restarts the stream "
                                              "while the head unit is slow: restart, none, or any.",
                                              "what", "any");
    QCommandLineOption mapSideOption("map-side",
                                     "Which half of the dashboard the owned map occupies, as the "
                                     "app is configured: left or right.", "side", "left");
    parser.addOptions({listenOption, connectOption, versionOption, traceOption, timeoutOption,
                       projectionOption, videoConfigOption, sensorsOption, mediaInfoOption, audioOption,
                       underflowOption, audioOutOption, marginsOption, touchOption,
                       touchSpaceOption, scriptOption, videoOutOption, holdOption,
                       toleranceOption, trackOption, trackIntervalOption, trackFileOption,
                       trackSpeedOption, mapSideOption, nightOption, callControlsOption,
                       navigationOption, navigationClusterOption, navigationIntervalOption,
                       navigationExpectOption, trafficModeOption, offerLayoutOption, etaOutOption,
                       weatherModeOption, weatherDirOption, positionOption, poiOption, poiAheadOption,
                       congestionKbpsOption, congestionFrameMsOption, congestionSlowOption,
                       congestionNormalOption, congestionExpectOption});
    parser.process(app);

    // How long a --track run without the track script waits before driving off.
    const int TRACK_KERB_MS = 5000;

    QTextStream out(stdout);
    const QStringList listen = parser.value(listenOption).split(':');
    const QStringList version = parser.value(versionOption).split('.');
    bool portOk = false, majorOk = false, minorOk = false, timeoutOk = false;
    const quint16 port = listen.size() == 2 ? listen[1].toUShort(&portOk) : 0;
    const quint16 major = version.size() == 2 ? version[0].toUShort(&majorOk) : 0;
    const quint16 minor = version.size() == 2 ? version[1].toUShort(&minorOk) : 0;
    const int timeoutMs = parser.value(timeoutOption).toInt(&timeoutOk);
    if (!portOk || !majorOk || !minorOk || !timeoutOk || timeoutMs <= 0) {
        QTextStream(stderr) << "invalid --listen, --request-version, or --timeout-ms\n";
        return 1;
    }
    const bool projection = parser.isSet(projectionOption);
    const int tolerance = parser.value(toleranceOption).toInt();
    const QString script = parser.value(scriptOption);
    if (!QStringList{"taps", "focus", "stall", "transport", "audio", "track", "navigation",
                     "guidance", "daynight", "controls", "endroute", "calls", "speech", "voice", "traffic", "weather",
                     "congestion", "none"}
             .contains(script)) {
        QTextStream(stderr) << "invalid --script\n";
        return 1;
    }

    Capabilities caps;
    caps.sensors = parser.isSet(sensorsOption);
    caps.sensorsAll = caps.sensors && parser.value(sensorsOption) == "all";
    if (caps.sensors && !QStringList{"restriction", "all"}.contains(parser.value(sensorsOption))) {
        QTextStream(stderr) << "invalid --sensors\n";
        return 1;
    }
    // A drive along road geometry (P6.5), read before anything connects so a bad file
    // fails the run at once rather than after the emulator has booted.
    std::vector<std::pair<double, double>> trackPath;
    if (parser.isSet(trackFileOption)) {
        QFile file(parser.value(trackFileOption));
        if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) {
            QTextStream(stderr) << "cannot read --track-file\n";
            return 1;
        }
        while (!file.atEnd()) {
            const QString line = QString::fromUtf8(file.readLine()).trimmed();
            if (line.isEmpty() || line.startsWith('#'))
                continue;
            const QStringList parts = line.split(',');
            bool latOk = false, lonOk = false;
            const double lat = parts.size() == 2 ? parts[0].toDouble(&latOk) : 0.0;
            const double lon = parts.size() == 2 ? parts[1].toDouble(&lonOk) : 0.0;
            if (!latOk || !lonOk || std::abs(lat) > 90.0 || std::abs(lon) > 180.0) {
                QTextStream(stderr) << "invalid --track-file line: " << line << "\n";
                return 1;
            }
            trackPath.emplace_back(lat, lon);
        }
        if (trackPath.size() < 2) {
            QTextStream(stderr) << "--track-file needs at least two points\n";
            return 1;
        }
    }
    const double trackSpeed = parser.value(trackSpeedOption).toDouble();
    if (!(trackSpeed > 0.0 && trackSpeed < 60.0)) {
        QTextStream(stderr) << "invalid --track-speed\n";
        return 1;
    }
    // P8.2: the voice script is the guidance drive with the voice toggle pressed on the way.
    const bool guidanceDrive = script == "guidance" || script == "voice";
    // P9: the traffic script parks at the drive's first point, and drives it only when off.
    const QString trafficMode = parser.value(trafficModeOption);
    if (!QStringList{"offer", "render", "closure", "off", "drive"}.contains(trafficMode)) {
        QTextStream(stderr) << "invalid --traffic-mode\n";
        return 1;
    }
    const bool offerStacked = parser.value(offerLayoutOption) == "stacked";
    if (!QStringList{"side-by-side", "stacked"}.contains(parser.value(offerLayoutOption))) {
        QTextStream(stderr) << "invalid --offer-layout\n";
        return 1;
    }
    if (script == "traffic" && (trackPath.empty() || !projection)) {
        QTextStream(stderr) << "--script traffic needs --projection and --track-file\n";
        return 1;
    }
    if (guidanceDrive && trackPath.empty()) {
        QTextStream(stderr) << "--script " << script << " needs --track-file\n";
        return 1;
    }
    {
        const QStringList at = parser.value(positionOption).split(',');
        bool latOk = false, lonOk = false;
        const double lat = at.size() == 2 ? at[0].toDouble(&latOk) : 0.0;
        const double lon = at.size() == 2 ? at[1].toDouble(&lonOk) : 0.0;
        if (!latOk || !lonOk || std::abs(lat) > 85.0 || std::abs(lon) > 180.0) {
            QTextStream(stderr) << "invalid --position\n";
            return 1;
        }
        g_fixLatitude = lat;
        g_fixLongitude = lon;
    }
    // P11.5: the lit kinds, in the app's button order.
    QStringList poiKinds;
    for (const QString& kind : parser.value(poiOption).split(',', Qt::SkipEmptyParts)) {
        if (!QStringList{"gas", "food", "shop"}.contains(kind.trimmed())) {
            QTextStream(stderr) << "invalid --poi\n";
            return 1;
        }
        poiKinds << kind.trimmed();
    }
    if (!poiKinds.isEmpty() && script != "track") {
        QTextStream(stderr) << "--poi is for --script track\n";
        return 1;
    }
    // P11.5a: the button to press once a route is followed, or empty.
    const QString poiAhead = parser.value(poiAheadOption).trimmed();
    if (!poiAhead.isEmpty() && (!QStringList{"gas", "food", "shop"}.contains(poiAhead) || script != "track")) {
        QTextStream(stderr) << "--poi-ahead is gas, food or shop, for --script track\n";
        return 1;
    }
    caps.track = parser.isSet(trackOption) || script == "track" || !trackPath.empty();
    if (caps.track) {
        // The track is LOCATION, CAR_SPEED and GPS, which only the full set advertises.
        caps.sensors = true;
        caps.sensorsAll = true;
    }
    // P7.6. The daynight script sends every flip itself, from day, so it takes no --night.
    const QString nightMode = parser.value(nightOption);
    if (nightMode == "day") {
        caps.night = false;
    } else if (nightMode.startsWith("flip:")) {
        bool secondsOk = false;
        const double seconds = nightMode.mid(5).toDouble(&secondsOk);
        if (!secondsOk || !(seconds > 0.0 && seconds <= 3600.0)) {
            QTextStream(stderr) << "invalid --night flip:<seconds>\n";
            return 1;
        }
        caps.night = false;
        caps.nightFlipMs = qRound(seconds * 1000.0);
    } else if (nightMode != "night") {
        QTextStream(stderr) << "invalid --night\n";
        return 1;
    }
    if (script == "daynight") {
        if (parser.isSet(nightOption)) {
            QTextStream(stderr) << "--script daynight sends NIGHT_DATA itself; drop --night\n";
            return 1;
        }
        if (!projection) {
            QTextStream(stderr) << "--script daynight needs --projection\n";
            return 1;
        }
        caps.night = false;
        // NIGHT_DATA through the lab's own handler, which can send it again later;
        // Prodigy's answers the subscription once.
        caps.sensors = true;
        caps.sensorsAll = true;
    }
    // P7.3. The day/night press is checked by the header turning light, which it does
    // only from the night the head unit says by default: Automatic at night, then Day.
    if (script == "controls") {
        if (parser.isSet(nightOption)) {
            QTextStream(stderr) << "--script controls needs the default night; drop --night\n";
            return 1;
        }
        if (!projection) {
            QTextStream(stderr) << "--script controls needs --projection\n";
            return 1;
        }
        if (!caps.sensorsAll) {
            QTextStream(stderr) << "--script controls needs --sensors all (NIGHT_DATA)\n";
            return 1;
        }
    }
    // P7.8, P7.9.
    const bool callsFull = parser.value(callControlsOption) == "full";
    if (!QStringList{"full", "basic"}.contains(parser.value(callControlsOption))) {
        QTextStream(stderr) << "invalid --call-controls\n";
        return 1;
    }
    // P10.
    const QString weatherMode = parser.value(weatherModeOption);
    if (!QStringList{"show", "tap", "alert", "stale", "unsupported", "off", "notifier"}.contains(weatherMode)) {
        QTextStream(stderr) << "invalid --weather-mode\n";
        return 1;
    }
    const QString weatherDir = parser.value(weatherDirOption);
    if (script == "weather" && weatherDir.isEmpty()) {
        QTextStream(stderr) << "--script weather needs --weather-dir\n";
        return 1;
    }
    if ((script == "calls" || script == "speech" || script == "weather") && !projection) {
        QTextStream(stderr) << "--script " << script << " needs --projection\n";
        return 1;
    }
    if (nightMode != "night" && !caps.sensorsAll) {
        QTextStream(stderr) << "--night needs --sensors all\n";
        return 1;
    }
    caps.trackIntervalMs = parser.value(trackIntervalOption).toInt();
    if (caps.trackIntervalMs <= 0) {
        QTextStream(stderr) << "invalid --track-interval-ms\n";
        return 1;
    }
    caps.mapOnLeft = parser.value(mapSideOption) == "left";
    if (!QStringList{"left", "right"}.contains(parser.value(mapSideOption))) {
        QTextStream(stderr) << "invalid --map-side\n";
        return 1;
    }
    if (script == "track" && !projection) {
        QTextStream(stderr) << "--script track needs --projection\n";
        return 1;
    }
    caps.mediaInfo = parser.isSet(mediaInfoOption) || script == "transport";
    caps.audio = parser.isSet(audioOption) || script == "audio";
    caps.navigation = parser.isSet(navigationOption) || script == "navigation" || guidanceDrive
                      || script == "endroute" || script == "traffic";
    caps.navigationCluster = parser.value(navigationClusterOption) == "start";
    if (!QStringList{"start", "none"}.contains(parser.value(navigationClusterOption))) {
        QTextStream(stderr) << "invalid --navigation-cluster\n";
        return 1;
    }
    caps.navigationIntervalMs = parser.value(navigationIntervalOption).toInt();
    if (caps.navigationIntervalMs <= 0) {
        QTextStream(stderr) << "invalid --navigation-interval-ms\n";
        return 1;
    }
    const QString navigationExpect = parser.value(navigationExpectOption);
    if (!QStringList{"auto", "sequence", "silence"}.contains(navigationExpect)) {
        QTextStream(stderr) << "invalid --navigation-expect\n";
        return 1;
    }
    caps.navigationExpectSequence = navigationExpect == "sequence"
                                    || (navigationExpect == "auto" && caps.navigationCluster);
    if (script == "navigation" && !projection) {
        QTextStream(stderr) << "--script navigation needs --projection\n";
        return 1;
    }
    if (script == "transport" && !projection) {
        QTextStream(stderr) << "--script transport needs --projection\n";
        return 1;
    }
    if (script == "audio" && !projection) {
        QTextStream(stderr) << "--script audio needs --projection\n";
        return 1;
    }
    const QStringList margins = parser.value(marginsOption).split('x');
    if (margins.size() == 2) {
        caps.marginWidth = margins[0].toInt();
        caps.marginHeight = margins[1].toInt();
    }
    const QStringList videoConfig = parser.value(videoConfigOption).split('x');
    if (videoConfig.size() == 2) {
        const int w = videoConfig[0].toInt();
        const int h = videoConfig[1].toInt();
        if ((w == 800 && h == 480) || (w == 1280 && h == 720) || (w == 1920 && h == 1080)) {
            caps.width = w;
            caps.height = h;
        } else {
            QTextStream(stderr) << "invalid --video-config\n";
            return 2;
        }
    }
    caps.touchWidth = caps.width - caps.marginWidth;
    caps.touchHeight = caps.height - caps.marginHeight;
    const QStringList touch = parser.value(touchOption).split('x');
    if (touch.size() == 2) {
        caps.touchWidth = touch[0].toInt();
        caps.touchHeight = touch[1].toInt();
    }
    caps.touchSpace = parser.value(touchSpaceOption);
    if (caps.touchSpace != "frame" && caps.touchSpace != "content"
        && caps.touchSpace != "advertised") {
        qCritical("--touch-space must be frame, content, or advertised");
        return 2;
    }

    QTcpServer server;
    QTcpSocket* clientSocket = nullptr;
    if (parser.isSet(connectOption)) {
        const QStringList target = parser.value(connectOption).split(':');
        if (target.size() != 2) {
            QTextStream(stderr) << "invalid --connect\n";
            return 1;
        }
        clientSocket = new QTcpSocket(&app);
        clientSocket->connectToHost(target[0], target[1].toUShort());
        if (!clientSocket->waitForConnected(10000)) {
            QTextStream(stderr) << "connect failed: " << clientSocket->errorString() << "\n";
            return 1;
        }
    } else if (!server.listen(QHostAddress(listen[0]), port)) {
        QTextStream(stderr) << "listen failed: " << server.errorString() << "\n";
        return 1;
    }
    out << "receiver=openauto-prodigy protocol library (lab)\n"
        << "receiver_requested_version=" << major << "." << minor << "\n"
        << "receiver_projection=" << (projection ? script : QString("off")) << "\n";
    if (projection) {
        const Layout l = caps.layout();
        out << "receiver_video_config=" << caps.width << "x" << caps.height
            << " margins=" << caps.marginWidth << "x" << caps.marginHeight
            << " content=" << l.x << "," << l.y << "+" << l.width << "x" << l.height
            << " touch=" << caps.touchWidth << "x" << caps.touchHeight << "\n";
    }
    if (clientSocket) {
        out << "receiver_connected=" << clientSocket->peerAddress().toString() << ":"
            << clientSocket->peerPort() << "\n";
    } else {
        out << "listening=" << server.serverAddress().toString() << ":" << server.serverPort()
            << "\n";
    }
    out.flush();

    oaa::SessionConfig config;
    config.protocolMajor = major;
    config.protocolMinor = minor;
    addChannels(config, caps);
    oaa::ProtocolLogger logger;
    logger.setFormat(oaa::ProtocolLogger::OutputFormat::Jsonl);
    logger.open(parser.value(traceOption).toStdString());

    Decoder decoder;
    if (projection && !decoder.open()) {
        QTextStream(stderr) << "cannot open the H.264 decoder\n";
        return 1;
    }
    QFile videoOut(parser.value(videoOutOption));
    if (!parser.value(videoOutOption).isEmpty() && !videoOut.open(QIODevice::WriteOnly)) {
        QTextStream(stderr) << "cannot write --video-out\n";
        return 1;
    }
    QFile etaOut(parser.value(etaOutOption));
    if (!parser.value(etaOutOption).isEmpty() && !etaOut.open(QIODevice::WriteOnly | QIODevice::Text)) {
        QTextStream(stderr) << "cannot write --eta-out\n";
        return 1;
    }
    QFile audioOut(parser.value(audioOutOption));
    if (!parser.value(audioOutOption).isEmpty() && !audioOut.open(QIODevice::WriteOnly)) {
        QTextStream(stderr) << "cannot write --audio-out\n";
        return 1;
    }

    // The moving position source (P6.1). It starts parked at the same inland fix the
    // lab has always reported, so a run begins from the picture the fixed fix produced
    // and the departure is the only thing that changed.
    std::unique_ptr<TrackPlayer> trackPlayer;
    if (caps.track) {
        trackPlayer = trackPath.empty()
                          ? std::make_unique<TrackPlayer>(g_fixLatitude, g_fixLongitude)
                          : std::make_unique<TrackPlayer>(trackPath, trackSpeed);
        // The track script releases it when it has a settled picture to measure
        // against, and the guidance script when the source has a route on the cluster;
        // every other use of --track has nothing watching, so it pulls away on its own
        // rather than sitting at the kerb for the whole run in silence.
        if (script != "track" && !guidanceDrive && script != "traffic") {
            QTimer::singleShot(TRACK_KERB_MS, &app, [&]() { trackPlayer->depart(); });
        }
    }

    oaa::TCPTransport* transport = nullptr;
    oaa::AASession* session = nullptr;
    LabVideoHandler* video = nullptr;
    oaa::hu::InputChannelHandler* input = nullptr;
    oaa::hu::SensorChannelHandler* sensors = nullptr;
    LabSensorHandler* labSensors = nullptr;
    oaa::hu::MediaStatusChannelHandler* media = nullptr;
    LabNavigationHandler* navigation = nullptr;
    LabAudioHandler* audio = nullptr;
    int status = 0;
    int checks = 0, failures = 0;
    // Video stream starts the source has sent (0x8001): the first, focus restarts, and
    // its own congestion restarts.
    int videoStarts = 0;
    int congestionStarts = 0, congestionFrames = 0, congestionErrors = 0;
    int taps = 0, keys = 0;
    // What the source published on the now-playing channel, as the receiver decoded it.
    int statusMessages = 0, metadataMessages = 0, albumArtBytes = 0;
    int playbackState = 0;
    QString sourceApp, trackTitle;
    // P5.3: what the audio channel did, from the receiver's own side.
    int audioSession = -1, audioConfigIndex = -1;
    bool audioStarted = false, audioStopped = false;
    // P6.2: what the cluster channel was told, decoded by Prodigy's own schemas.
    QStringList navStates;
    // P7.4: every navigation focus request the source made (1 native, 2 projected, 0
    // unreadable), and how many cluster states had arrived when it came, so a script can
    // say which route a request belongs to. Prodigy answers each by echoing it; this is
    // the record of what was asked, which is the evidence.
    QList<int> navFocusTypes, navFocusAtState;
    int navManeuver = -1, navExit = -1;
    QString navStepDistance, navCurrentRoad;
    int navStepUnit = -1, navStepValue = 0;
    qint64 navTimeToStep = -1, navTimeToArrival = -1;
    int navLanes = 0, navDestinations = 0;
    bool navEtaString = false;
    // P9.4: the cluster's time to the destination as it changed, with the first rise and the
    // largest one while the cluster's state held, which is live traffic's delay arriving.
    qint64 etaLast = -1;
    int etaStates = -1;
    qint64 etaFirstRiseFrom = -1, etaFirstRiseTo = -1, etaMaxRise = 0;
    // P6.5: the drive, as the cluster was told it. Each maneuver as it changed (two
    // like maneuvers in a row read as one, since only a change is seen); the countdown
    // compared message to message while one maneuver holds; and where the drive was.
    QList<int> guideManeuvers;
    // Counts of what carried a road name, never the names: they say where the drive went.
    int guideSteps = 0, guideNamedSteps = 0, guideCurrentRoads = 0;
    int guideFalls = 0, guideRises = 0, guideEpoch = -1;
    double guideLastMetres = -1.0, guideWorstRise = 0.0;
    QElapsedTimer guideArrival;
    QElapsedTimer uptime;
    uptime.start();

    auto fail = [&](const QString& message) {
        ++failures;
        out << "receiver_check_failed=" << message << "\n";
        out.flush();
    };
    auto check = [&](bool ok, const QString& message) {
        ++checks;
        if (!ok)
            fail(message);
        return ok;
    };

    // P7.6: run after every decoded picture, once the day/night script has set it.
    std::function<void()> onPicture;

    auto startSession = [&](QTcpSocket* socket) {
        transport = new oaa::TCPTransport(&app);
        transport->setSocket(socket);
        transport->start();
        session = new oaa::AASession(transport, config, &app);
        logger.attach(session->messenger());
        QObject::connect(session->controlChannel(), &oaa::ControlChannel::navigationFocusRequested, &app,
                         [&](const QByteArray& payload) {
                             oaa::proto::messages::NavigationFocusRequest request;
                             const int type = request.ParseFromArray(payload.constData(), payload.size())
                                                      && request.has_type()
                                                  ? static_cast<int>(request.type())
                                                  : 0;
                             navFocusTypes << type;
                             navFocusAtState << navStates.size();
                             out << "receiver_navigation_focus_request=type=" << type
                                 << " states=" << navStates.join(',') << "\n";
                             out.flush();
                         });
        if (projection) {
            video = new LabVideoHandler(caps.video, &app);
            video->setNumVideoConfigs(caps.configs);
            input = new oaa::hu::InputChannelHandler(caps.input, &app);
            session->registerChannel(caps.video, video);
            session->registerChannel(caps.input, input);
            if (caps.sensorsAll) {
                labSensors = new LabSensorHandler(trackPlayer.get(), caps.trackIntervalMs,
                                                  caps.night, caps.nightFlipMs, &app);
                session->registerChannel(oaa::ChannelId::Sensor, labSensors);
            } else if (caps.sensors) {
                sensors = new oaa::hu::SensorChannelHandler(&app);
                session->registerChannel(oaa::ChannelId::Sensor, sensors);
            }
            if (caps.audio) {
                audio = new LabAudioHandler(oaa::ChannelId::MediaAudio, &app);
                audio->underflowAfter = parser.value(underflowOption).toInt();
                session->registerChannel(oaa::ChannelId::MediaAudio, audio);
                QObject::connect(audio, &oaa::hu::AudioChannelHandler::streamStartDetailsReceived,
                                 &app,
                                 [&](int32_t sessionId, uint32_t configIndex, int, bool,
                                     const QString&) {
                                     audioStarted = true;
                                     audioSession = sessionId;
                                     audioConfigIndex = static_cast<int>(configIndex);
                                     out << "receiver_audio_started=session=" << sessionId
                                         << " config=" << configIndex << "\n";
                                     out.flush();
                                 });
                QObject::connect(audio, &oaa::hu::AudioChannelHandler::streamStopped, &app,
                                 [&]() { audioStopped = true; });
                QObject::connect(audio, &oaa::hu::AudioChannelHandler::audioDataReceived, &app,
                                 [&](const QByteArray& data, uint64_t) {
                                     if (audioOut.isOpen())
                                         audioOut.write(data);
                                 });
            }
            if (caps.navigation) {
                navigation = new LabNavigationHandler(caps.navigationCluster, &app);
                session->registerChannel(oaa::ChannelId::Navigation, navigation);
                QObject::connect(
                    navigation, &oaa::hu::NavigationChannelHandler::navigationStateSnapshotChanged,
                    &app, [&](oaa::hu::NavigationState state) {
                        navStates << QString::number(static_cast<int>(state));
                        out << "receiver_navigation_state=" << static_cast<int>(state) << "\n";
                        out.flush();
                    });
                QObject::connect(
                    navigation, &oaa::hu::NavigationChannelHandler::navigationNotificationChanged,
                    &app, [&](const oaa::hu::NavigationNotificationSnapshot& snapshot) {
                        navManeuver = snapshot.hasManeuver ? snapshot.maneuverType : -1;
                        if (snapshot.hasManeuver
                            && (guideManeuvers.isEmpty() || guideManeuvers.last() != navManeuver))
                            guideManeuvers << navManeuver;
                        if (snapshot.hasManeuver) {
                            guideSteps++;
                            // road_info's names. Prodigy files them as "action cues"
                            // and calls the instruction text the upcoming road.
                            if (!snapshot.actionCues.isEmpty())
                                guideNamedSteps++;
                        }
                        navLanes = snapshot.lanes.size();
                        navDestinations = snapshot.destinations.size();
                        // The road name and the instruction are not printed: they are
                        // scripted here, and the rule they would establish is not (P6
                        // plan §9). The maneuver and the counts are the evidence.
                        out << "receiver_navigation_step=maneuver=" << navManeuver
                            << " steps=" << snapshot.stepCount << " lanes=" << navLanes
                            << " destinations=" << navDestinations << "\n";
                        out.flush();
                    });
                QObject::connect(
                    navigation, &oaa::hu::NavigationChannelHandler::navigationPositionChanged, &app,
                    [&](const oaa::hu::NavigationPositionSnapshot& snapshot) {
                        if (snapshot.hasStepDistance) {
                            navStepDistance = snapshot.stepDistance.displayText;
                            navStepUnit = snapshot.stepDistance.hasUnit
                                              ? snapshot.stepDistance.unit
                                              : -1;
                            navStepValue = snapshot.stepDistance.value;
                            // The countdown in metres, from the unit and the value rather
                            // than the text. A new maneuver or a new state starts a new
                            // countdown, which may well be longer than the last one.
                            double metres = -1.0;
                            switch (navStepUnit) {
                            case 1: metres = navStepValue; break;
                            case 2: metres = navStepValue * 1000.0; break;
                            case 3: metres = navStepValue * 100.0; break;
                            case 4: metres = navStepValue * 1609.344; break;
                            case 5: metres = navStepValue * 160.9344; break;
                            case 6: metres = navStepValue * 0.3048; break;
                            case 7: metres = navStepValue * 0.9144; break;
                            default: break;
                            }
                            const int epoch = navigation->notifications * 16 + navStates.size();
                            if (metres >= 0.0 && epoch == guideEpoch && guideLastMetres >= 0.0) {
                                if (metres < guideLastMetres)
                                    ++guideFalls;
                                else if (metres > guideLastMetres) {
                                    ++guideRises;
                                    guideWorstRise = std::max(guideWorstRise, metres - guideLastMetres);
                                }
                            }
                            guideEpoch = epoch;
                            guideLastMetres = metres;
                        }
                        navTimeToStep = snapshot.hasTimeToStep ? snapshot.timeToStepSeconds : -1;
                        navCurrentRoad = snapshot.hasCurrentRoad ? snapshot.currentRoad
                                                                 : QString();
                        if (!navCurrentRoad.isEmpty())
                            guideCurrentRoads++;
                        for (const auto& destination : snapshot.destinationDistances) {
                            if (destination.hasTimeToArrival)
                                navTimeToArrival = destination.timeToArrivalSeconds;
                            if (destination.hasEstimatedTimeOfArrival)
                                navEtaString = true;
                        }
                        if (navTimeToArrival >= 0 && navTimeToArrival != etaLast) {
                            if (etaOut.isOpen()) {
                                etaOut.write(QString("%1 %2 %3\n").arg(QDateTime::currentMSecsSinceEpoch())
                                                 .arg(navTimeToArrival).arg(navStates.join(',')).toUtf8());
                                etaOut.flush();
                            }
                            // A rise of more than a minute with the state unchanged is not the
                            // countdown: it is a delay added to the same route.
                            if (etaLast >= 0 && etaStates == navStates.size()) {
                                const qint64 rise = navTimeToArrival - etaLast;
                                if (rise > 60 && etaFirstRiseFrom < 0) {
                                    etaFirstRiseFrom = etaLast;
                                    etaFirstRiseTo = navTimeToArrival;
                                }
                                etaMaxRise = std::max(etaMaxRise, rise);
                            }
                            etaLast = navTimeToArrival;
                            etaStates = navStates.size();
                        }
                    });
            }
            if (caps.mediaInfo) {
                media = new oaa::hu::MediaStatusChannelHandler(&app);
                session->registerChannel(oaa::ChannelId::MediaStatus, media);
                QObject::connect(media,
                                 &oaa::hu::MediaStatusChannelHandler::playbackStateChanged, &app,
                                 [&](int state, const QString& appName) {
                                     ++statusMessages;
                                     playbackState = state;
                                     sourceApp = appName;
                                     out << "receiver_playback=" << state
                                         << " source=" << appName << "\n";
                                     out.flush();
                                 });
                QObject::connect(media, &oaa::hu::MediaStatusChannelHandler::metadataChanged, &app,
                                 [&](const QString& title, const QString& artist,
                                     const QString& album, const QByteArray& art) {
                                     ++metadataMessages;
                                     trackTitle = title;
                                     albumArtBytes = art.size();
                                     out << "receiver_metadata=" << title << " — " << artist
                                         << " — " << album << " art=" << art.size() << "\n";
                                     out.flush();
                                 });
            }
            QObject::connect(video, &oaa::hu::VideoChannelHandler::videoFrameData, &app,
                             [&](std::shared_ptr<const QByteArray> data, qint64) {
                                 if (videoOut.isOpen())
                                     videoOut.write(*data);
                                 if (decoder.decode(*data) > 0 && onPicture)
                                     onPicture();
                             });
            QObject::connect(video, &oaa::hu::VideoChannelHandler::streamStarted, &app,
                             [&](int32_t session_id, uint32_t configIndex) {
                                 ++videoStarts;
                                 out << "receiver_video_started=session=" << session_id
                                     << " config=" << configIndex << "\n";
                                 out.flush();
                             });
        }
        QObject::connect(session, &oaa::AASession::stateChanged, &app, [&](oaa::SessionState state) {
            out << "receiver_state=" << stateName(state) << "\n";
            out.flush();
        });
        QObject::connect(session, &oaa::AASession::channelOpened, &app, [&](uint8_t channel) {
            out << "receiver_channel_opened=" << channel << "\n";
            out.flush();
        });
        QObject::connect(session, &oaa::AASession::channelOpenRejected, &app, [&](int32_t channel) {
            out << "receiver_channel_rejected=" << channel << "\n";
            out.flush();
        });
        QObject::connect(session, &oaa::AASession::disconnected, &app, [&](oaa::DisconnectReason reason) {
            out << "receiver_disconnected=" << reasonName(reason) << "\n";
            out.flush();
            QTimer::singleShot(0, &app, [&]() { app.exit(status); });
        });
        session->start();
    };

    if (clientSocket) {
        startSession(clientSocket);
    } else {
        QObject::connect(&server, &QTcpServer::newConnection, &app, [&]() {
            QTcpSocket* socket = server.nextPendingConnection();
            if (!socket || session)
                return;
            server.close();
            out << "receiver_peer=" << socket->peerAddress().toString() << ":" << socket->peerPort()
                << "\n";
            out.flush();
            startSession(socket);
        });
    }

    // The projection script, driven by a timer so the event loop keeps decoding.
    int step = 0;
    int stepFrames = 0;
    qint64 stepDeadline = 0;
    Yuv before{};
    // What the now-playing channel said before the transport script pressed a button.
    int stepState = 0;
    QString stepTitle;
    QTimer scriptTimer;
    const int holdMs = parser.value(holdOption).toInt();
    // The congestion script: normal, slow for a while, then normal again.
    const int congestionKbps = parser.value(congestionKbpsOption).toInt();
    const int congestionFrameMs = parser.value(congestionFrameMsOption).toInt();
    const int congestionSlowMs = parser.value(congestionSlowOption).toInt();
    const int congestionNormalMs = parser.value(congestionNormalOption).toInt();
    const QString congestionExpect = parser.value(congestionExpectOption);
    if (!QStringList{"restart", "none", "any"}.contains(congestionExpect)) {
        QTextStream(stderr) << "invalid --congestion-expect\n";
        return 1;
    }
    // How long a tapped button has to appear in a decoded picture.
    const qint64 TAP_DEADLINE_MS = 4000;
    // Audio buffers to collect before measuring the tone, and the magnitude a real tone
    // has to beat. The source sends at a quarter of full scale, so anything above a few
    // percent is signal rather than noise.
    const int TONE_BUFFERS = 4;
    const double TONE_FLOOR = 0.05;
    // One outbound fragment less the message id and timestamp: an audio message larger
    // than this would fragment and could interleave with a video keyframe.
    const int AUDIO_FRAGMENT_LIMIT = 16 * 1024 - 10;
    // P6.2. How long a cluster expecting silence is watched before the silence counts.
    // Longer than the source's own cluster wait, so a source that gives up and sends
    // anyway has had time to do it.
    //
    // Measured from the first script tick, which is after the first decoded picture, and
    // **not** from this process starting: the emulator lab launches the receiver and then
    // spends minutes booting Android, so a window timed from process start was already
    // satisfied the moment projection began. That reported a silence nobody had watched
    // for, and it hid whether the source had reached its own cluster deadline at all.
    const qint64 NAV_SILENCE_MS = 12000;
    QElapsedTimer navSilence;
    // P6.1. Where the two panes are, and what counts as one of them having moved.
    const DashboardPane pane = DashboardPane::of(caps.layout(), caps.mapOnLeft, offerStacked);
    QByteArray mapBefore, tilesBefore;
    double trackMark = 0.0;
    int trackSettleTries = 0;
    // How often the parked map is sampled while it settles, and how long to wait for it
    // to go quiet before giving up and driving anyway: a map that never settles is a
    // failed check, not a reason to collect no evidence at all.
    const qint64 TRACK_SAMPLE_MS = 1000;
    const int TRACK_SETTLE_TRIES = 25;
    // Luma difference that counts as a changed pixel: above the encoder's noise on a
    // static picture, well below any real change in what is drawn.
    const int TRACK_LUMA_THRESHOLD = 12;
    // Fractions of a pane. A map panning by tens of pixels changes most of itself; the
    // static half changes nothing but encoder noise.
    const double TRACK_MOVED_MIN = 0.10;
    const double TRACK_STATIC_MAX = 0.02;
    // How long a parked vehicle at the end of the guidance drive waits for the source to
    // say it has arrived.
    const int GUIDE_ARRIVAL_MS = 15000;
    // P8.2: how much of the voice toggle's box a press must change. On to off loses the
    // accent speaker's two waves and gains a slash; about a tenth of the box at 1080p.
    const double VOICE_TOGGLE_MIN = 0.03;
    // And how little of it may differ once it is back on: the same glyph as before.
    const double VOICE_TOGGLE_SAME_MAX = 0.02;
    // Metres of travel per checked interval: about ten seconds at the track's cruise.
    const double TRACK_STEP_METRES = 120.0;
    auto paneLuma = [&](const DashboardPane::Rect& r) {
        return decoder.luma(r.x, r.y, r.width, r.height);
    };
    // P11.5 (--poi): the markers' colours in the decoded picture, counted in the map pane
    // under the button row. PoiRules.colour: violet #D500F9 (U 206, V 204), orange #FF6D00
    // (U 59, V 200) and green #00B050 (U 112, V 58) in BT.601, a few units off in BT.709 -
    // none near the map themes' own colours, the route's cyan or the chrome's accent.
    const QString POI_NAMES[3] = {"gas", "food", "shop"};
    const int POI_U[3] = {206, 59, 112}, POI_V[3] = {204, 200, 58};
    const int POI_TOLERANCE = 26;
    // A third of one marker's disc at 800x480; a whole marker is about 500 pixels there.
    const int POI_MIN = 150;
    const qint64 POI_SAMPLE_MS = 500, POI_WAIT_MS = 120000, POI_PALETTE_MS = 10000, POI_SETTLE_MS = 10000;
    qint64 poiStart = 0;
    struct PoiCounts {
        int kind[3] = {-1, -1, -1};
    };
    QByteArray poiTileBefore;
    // P11.5a (--poi-ahead): the lab script's go-ahead file, the press, and whether it is done.
    const QString POI_AHEAD_GO = "/out/poi-ahead-go";
    const qint64 POI_AHEAD_GO_MS = 300000, POI_AHEAD_CARD_MS = 360000, POI_AHEAD_SAMPLE_MS = 500;
    qint64 poiAheadAt = 0;
    bool poiAheadDone = poiAhead.isEmpty();
    // The centre of the first 3x3 window wholly in kind [i]'s colour, under the button row: at
    // 726x480 a marker's coloured ring between its white glyph and its rim is 3-4 px wide.
    auto poiFind = [&](int i, QPoint& at) {
        const DashboardPane::Rect& full = pane.mapFull;
        const int margin = std::max(2, caps.layout().width / 96);
        const int bubble = std::max(24, std::min(full.width, full.height) / 8);
        const int top = full.y + margin + bubble + margin;
        const int cu = POI_U[i], cv = POI_V[i];
        auto test = [&](int, int u, int v) { return std::abs(u - cu) <= POI_TOLERANCE && std::abs(v - cv) <= POI_TOLERANCE; };
        for (int y = top; y + 3 <= full.y + full.height - margin; y += 1) {
            for (int x = full.x + margin; x + 3 <= full.x + full.width - margin; x += 1) {
                if (decoder.count(x, y, 3, 3, test) == 9) {
                    at = QPoint(x + 1, y + 1);
                    return true;
                }
            }
        }
        return false;
    };
    auto poiPress = [&](QPoint at, const QString& what) {
        const QPoint touchPoint = caps.toTouch(at);
        const oaa::hu::InputChannelHandler::Pointer pointer{touchPoint.x(), touchPoint.y(), 0};
        input->sendTouchIndication(1, &pointer, 0, 0, uptime.elapsed() * 1000);
        input->sendTouchIndication(1, &pointer, 0, 1, uptime.elapsed() * 1000 + 100);
        ++taps;
        out << "receiver_poi=press " << what << " frame=" << at.x() << "," << at.y() << " touch=" << touchPoint.x() << ","
            << touchPoint.y() << " picture=" << decoder.frames << "\n";
        out.flush();
    };
    auto poiCounts = [&]() {
        PoiCounts counts;
        const DashboardPane::Rect& full = pane.mapFull;
        const int margin = std::max(2, caps.layout().width / 96);
        const int bubble = std::max(24, std::min(full.width, full.height) / 8);
        const int top = full.y + margin + bubble + margin;
        const DashboardPane::Rect below = {full.x, top, full.width, std::max(0, full.y + full.height - top)};
        for (int i = 0; i < 3; ++i) {
            const int cu = POI_U[i], cv = POI_V[i];
            counts.kind[i] = decoder.count(below.x, below.y, below.width, below.height, [&](int, int u, int v) {
                return std::abs(u - cu) <= POI_TOLERANCE && std::abs(v - cv) <= POI_TOLERANCE;
            });
        }
        return counts;
    };
    if (projection && caps.track) {
        out << "receiver_track_panes=map=" << pane.map.x << "," << pane.map.y << "+"
            << pane.map.width << "x" << pane.map.height << " tiles=" << pane.tiles.x << ","
            << pane.tiles.y << "+" << pane.tiles.width << "x" << pane.tiles.height
            << " map_on_left=" << (caps.mapOnLeft ? "true" : "false")
            << " interval_ms=" << caps.trackIntervalMs << "\n";
        out.flush();
    }
    // P7.6. One day/night flip as the decoded pictures showed it. The script sends
    // NIGHT_DATA and this watches every picture that follows, rather than sampling on the
    // script's 100 ms tick, because the question is precisely what is on screen between
    // the flag and the finished new theme: the old picture held, a blank pane, or a map
    // drawn half in one theme and half missing. A tick could step over all three.
    struct FlipFrame {
        qint64 ms = 0;
        QByteArray thin;
        double stddev = 0.0;
        double mean = 0.0;
    };
    struct FlipWatch {
        bool active = false;
        bool done = false;
        bool toNight = true;
        int index = 0;
        QElapsedTimer since;
        QByteArray before, beforeThin, previous, settled;
        double beforeMean = 0.0;
        std::vector<FlipFrame> frames;
        qint64 firstChangeMs = -1;
        qint64 lastChangeMs = -1;
        qint64 settledMs = -1;
        qint64 maxGapMs = 0;
        qint64 sentAtVideoMs = 0;
    } flip;
    QByteArray dayFirst, nightFirst;
    qint64 firstPictureMs = -1, lastPictureMs = -1;
    // Frame-to-frame gaps before the flip, for the median a flip's worst gap is set against.
    std::vector<qint64> recentGaps;
    // Pixels kept per thinned picture: every fourth in each direction.
    const int FLIP_THIN_STEP = 4;
    // A picture that has left the old one by this much is the new theme arriving.
    const double FLIP_CHANGED_MIN = 0.30;
    // Settled: no picture changed from the one before it (beyond TRACK_STATIC_MAX) for
    // this long. Long enough to include labels, which VTM places after the tiles.
    const qint64 FLIP_QUIET_MS = 1500;
    const qint64 FLIP_DEADLINE_MS = 20000;
    // A picture within this of the old or the settled one is that picture.
    const double FLIP_SAME_MAX = 0.05;
    // Further than this from both is a map caught half-drawn.
    const double FLIP_PARTIAL_MIN = 0.25;
    // Luma spread below which the pane holds nothing but a background.
    const double FLIP_BLANK_SD = 4.0;
    // A theme change repaints the ground everywhere, so most of the pane must change,
    // and in the right direction: darker for night, lighter for day.
    const double FLIP_THEME_MIN = 0.50;
    const double FLIP_LUMA_SHIFT = 40.0;
    // The second day (and night) against the first: the same picture, or a tile stuck.
    const double FLIP_REPEAT_MAX = 0.05;
    const size_t FLIP_MAX_FRAMES = 1200;
    const qint64 FLIP_GAP_MAX_MS = 1000;
    if (projection && script == "daynight") {
        onPicture = [&]() {
            const qint64 now = uptime.elapsed();
            if (firstPictureMs < 0)
                firstPictureMs = now;
            const qint64 gap = lastPictureMs < 0 ? 0 : now - lastPictureMs;
            lastPictureMs = now;
            if (!flip.active) {
                recentGaps.push_back(gap);
                if (recentGaps.size() > 150)
                    recentGaps.erase(recentGaps.begin());
                return;
            }
            flip.maxGapMs = std::max(flip.maxGapMs, gap);
            const QByteArray map = paneLuma(pane.map);
            if (map.isEmpty())
                return;
            const qint64 t = flip.since.elapsed();
            FlipFrame frame;
            frame.ms = t;
            frame.thin = thinLuma(map, pane.map.width, FLIP_THIN_STEP);
            lumaStats(map, frame.mean, frame.stddev);
            if (flip.frames.size() < FLIP_MAX_FRAMES)
                flip.frames.push_back(frame);
            const double fromBefore = changedFraction(flip.before, map, TRACK_LUMA_THRESHOLD);
            const double fromPrevious = changedFraction(flip.previous, map, TRACK_LUMA_THRESHOLD);
            flip.previous = map;
            if (flip.firstChangeMs < 0) {
                if (fromBefore > FLIP_CHANGED_MIN)
                    flip.firstChangeMs = flip.lastChangeMs = t;
            } else if (fromPrevious >= TRACK_STATIC_MAX) {
                flip.lastChangeMs = t;
            }
            const bool quiet = flip.firstChangeMs >= 0 && t - flip.lastChangeMs >= FLIP_QUIET_MS;
            if (quiet || t > FLIP_DEADLINE_MS) {
                flip.settledMs = quiet ? flip.lastChangeMs : -1;
                flip.settled = map;
                flip.active = false;
                flip.done = true;
            }
        };
    }
    // Sends the flag and starts watching. The pane as it is now is "before".
    auto startFlip = [&](bool toNight) {
        const int index = flip.index + 1;
        flip = FlipWatch{};
        flip.index = index;
        flip.toNight = toNight;
        flip.before = paneLuma(pane.map);
        flip.beforeThin = thinLuma(flip.before, pane.map.width, FLIP_THIN_STEP);
        double spread = 0.0;
        lumaStats(flip.before, flip.beforeMean, spread);
        flip.previous = flip.before;
        flip.sentAtVideoMs = firstPictureMs < 0 ? 0 : uptime.elapsed() - firstPictureMs;
        flip.since.start();
        flip.active = true;
        const bool sent = labSensors && labSensors->sendNight(toNight);
        check(sent, QString("NIGHT_DATA %1 could not be sent").arg(toNight ? "true" : "false"));
        out << "receiver_daynight_sent=" << index << " is_night=" << (toNight ? "true" : "false")
            << " video_s=" << (flip.sentAtVideoMs / 1000.0) << " pane_mean=" << qRound(flip.beforeMean)
            << "\n";
        out.flush();
    };
    // What the pictures between the flag and the settled theme were, and the checks on
    // them. The gap figures are the scene's frame delivery around the switch: a scene
    // thread stalled by the switch shows up as one long gap between pictures.
    auto finishFlip = [&]() {
        const QByteArray settledThin = thinLuma(flip.settled, pane.map.width, FLIP_THIN_STEP);
        int oldFrames = 0, newFrames = 0, between = 0, partial = 0, blank = 0;
        qint64 lastOldMs = -1, firstNewMs = -1;
        for (const FlipFrame& f : flip.frames) {
            if (f.stddev < FLIP_BLANK_SD) {
                ++blank;
                continue;
            }
            const double fromOld = changedFraction(flip.beforeThin, f.thin, TRACK_LUMA_THRESHOLD);
            const double fromNew = changedFraction(settledThin, f.thin, TRACK_LUMA_THRESHOLD);
            if (fromOld >= 0.0 && fromOld < FLIP_SAME_MAX) {
                ++oldFrames;
                lastOldMs = f.ms;
            } else if (fromNew >= 0.0 && fromNew < FLIP_SAME_MAX) {
                ++newFrames;
                if (firstNewMs < 0)
                    firstNewMs = f.ms;
            } else {
                const bool half = fromOld > FLIP_PARTIAL_MIN && fromNew > FLIP_PARTIAL_MIN;
                ++(half ? partial : between);
                // Each picture that was neither theme, so a failure says what it looked like.
                out << "receiver_daynight_picture=" << flip.index << " ms=" << f.ms
                    << " from_old=" << fromOld << " from_settled=" << fromNew
                    << " mean=" << qRound(f.mean) << " spread=" << qRound(f.stddev)
                    << (half ? " half-drawn" : "") << "\n";
            }
        }
        double settledMean = 0.0, settledSpread = 0.0;
        lumaStats(flip.settled, settledMean, settledSpread);
        const double changed = changedFraction(flip.before, flip.settled, TRACK_LUMA_THRESHOLD);
        std::vector<qint64> gaps = recentGaps;
        std::sort(gaps.begin(), gaps.end());
        const qint64 medianGap = gaps.empty() ? -1 : gaps[gaps.size() / 2];
        const char* to = flip.toNight ? "night" : "day";
        out << "receiver_daynight=" << flip.index << " to=" << to
            << " first_change_ms=" << flip.firstChangeMs << " settled_ms=" << flip.settledMs
            << " last_old_ms=" << lastOldMs << " first_settled_look_ms=" << firstNewMs
            << " pictures=" << flip.frames.size() << " old=" << oldFrames << " new=" << newFrames
            << " between=" << between << " partial=" << partial << " blank=" << blank
            << " pane_changed=" << changed << " pane_mean=" << qRound(flip.beforeMean) << "->"
            << qRound(settledMean) << " max_gap_ms=" << flip.maxGapMs
            << " median_gap_ms_before=" << medianGap << " video_s=" << (flip.sentAtVideoMs / 1000.0)
            << "\n";
        out.flush();
        check(flip.settledMs >= 0,
              QString("flip %1 to %2 did not settle within %3 ms").arg(flip.index).arg(to).arg(FLIP_DEADLINE_MS));
        check(changed > FLIP_THEME_MIN,
              QString("flip %1 to %2 changed only %3 of the map pane").arg(flip.index).arg(to).arg(changed));
        check(flip.toNight ? settledMean < flip.beforeMean - FLIP_LUMA_SHIFT
                           : settledMean > flip.beforeMean + FLIP_LUMA_SHIFT,
              QString("flip %1 to %2 took the pane's mean luma from %3 to %4")
                  .arg(flip.index).arg(to).arg(flip.beforeMean).arg(settledMean));
        check(blank == 0, QString("flip %1 to %2 showed %3 blank map pictures").arg(flip.index).arg(to).arg(blank));
        check(partial == 0,
              QString("flip %1 to %2 showed %3 half-drawn map pictures").arg(flip.index).arg(to).arg(partial));
        check(flip.maxGapMs < FLIP_GAP_MAX_MS,
              QString("flip %1 to %2 stalled the stream for %3 ms").arg(flip.index).arg(to).arg(flip.maxGapMs));
    };
    // P7.2, P7.3. The controls script: each press is on a settled picture, and is checked
    // against the part of the picture it should change. The order is fixed: the report
    // side (tools/emulator-lab.sh) expects zoom 17, the pause on, and Day.
    struct ControlPress {
        const char* name;
        QPoint at;
        DashboardPane::Rect watch;
        double minChanged;
    };
    const std::vector<ControlPress> controlPresses = {
        {"zoom-in", pane.zoomIn, pane.map, TRACK_MOVED_MIN},
        {"zoom-in", pane.zoomIn, pane.map, TRACK_MOVED_MIN},
        {"zoom-out", pane.zoomOut, pane.map, TRACK_MOVED_MIN},
        // Thin grey ring to bold accent ring and letter: a few percent of its target.
        {"pause", pane.pauseIcon, pane.pauseBox, 0.02},
        // Night palette to day: the whole header strip, and lighter (checked below).
        {"day-night", pane.dayNightIcon, pane.header, 0.50},
    };
    size_t controlIndex = 0;
    // P7.4/P7.5. The endroute script: the trip panel and card before End route, and the
    // cluster's state list when it was pressed, so what followed the press can be read.
    QByteArray endTileBefore, endCardBefore, endMediaBefore;
    int endStatesAtPress = -1;
    double endTileChanged = -1.0, endCardChanged = -1.0, endMediaChanged = -1.0;
    // P7.4a: where the watch after the first End route ended (states, focus requests), and
    // the state count at the second press, so each focus request can be placed between
    // the presses and the routes around them.
    int endStatesAtWatch = -1, endFocusAtWatch = -1, endStatesAtSecond = -1, endFocusAtSecond = -1;
    QByteArray controlBefore;
    // P7.8/P7.9. The calls script: the picture a press is measured against, which key is
    // next, and what each press changed, for the summary line.
    QByteArray callsBefore;
    // P8.1. The speech script: the flex tile before the first tap, unlit.
    QByteArray speechUnlit;
    // P8.2. The voice script: the toggle's picture on, then off, and how much each press
    // changed it; the decoded frame of each state, so its picture can be found in frames/.
    QByteArray voiceOnLuma, voiceOffLuma;
    double voiceOffChanged = -1.0, voiceOnChanged = -1.0, voiceBackToOn = -1.0;
    int voiceOffFrame = -1, voiceOnFrame = -1;
    int callsKey = 0;
    QStringList callsResults;
    const int CALLS_KEYS[3] = {0, 1, 11}; // "1", "2" and "#"
    // A plate is green when its chroma is well below neutral in both: BT.601 green has
    // V near 90 and U near 112 in both palettes' Answer, a grey or dark tile has 128.
    auto answerGreen = [&]() {
        const Yuv patch = decoder.sample(pane.answerPatch.x(), pane.answerPatch.y());
        return patch.y >= 0 && patch.v < 110 && patch.u < 124;
    };
    auto callsPress = [&](QPoint at, const char* what) {
        const QPoint touchPoint = caps.toTouch(at);
        const oaa::hu::InputChannelHandler::Pointer pointer{touchPoint.x(), touchPoint.y(), 0};
        input->sendTouchIndication(1, &pointer, 0, 0, uptime.elapsed() * 1000);
        input->sendTouchIndication(1, &pointer, 0, 1, uptime.elapsed() * 1000 + 1);
        ++taps;
        out << "receiver_calls=press " << what << " frame=" << at.x() << "," << at.y()
            << " touch=" << touchPoint.x() << "," << touchPoint.y() << "\n";
        out.flush();
    };
    // Waits for [box] to change by [min] from callsBefore, or for the deadline; then checks
    // it and moves on. Returns false while still waiting.
    auto callsChanged = [&](const DashboardPane::Rect& box, double min, const char* what) {
        const double changed = changedFraction(callsBefore, paneLuma(box), TRACK_LUMA_THRESHOLD);
        if (changed < min && uptime.elapsed() < stepDeadline)
            return false;
        out << "receiver_calls=changed " << what << " " << changed << "\n";
        out.flush();
        callsResults << QString("%1=%2").arg(what).arg(changed, 0, 'f', 3);
        check(changed >= min, QString("%1 changed only %2 of what it should").arg(what).arg(changed));
        return true;
    };
    double controlBeforeMean = 0.0;
    int controlSettleTries = 0;
    // P9 (the traffic script). Colours are judged in YUV off the decoded picture: amber
    // (TrafficLines.SLOW_COLOUR, #F59A00: U about 48, V 179) is further from every colour
    // of both map themes than anything else it draws, so it is the one counted for the
    // route line changing colour; the queuing red (#F2362B) sits near the night theme's
    // motorway red and is only reported. The closure's dark red (#7A0A0A: Y 54, V 177) is
    // counted while the closure is on the route. The offer is up when Take is filled with
    // the palette's accent (#1D64C0 by day, #4E9AF1 at night: U about 178, V 90) and Keep
    // is not, which no map colour and no other state of the card produces.
    auto amberPixel = [](int, int u, int v) { return u < 75 && v > 155; };
    auto redPixel = [](int y, int u, int v) { return v >= 200 && u <= 110 && y >= 80 && y <= 145; };
    auto closedPixel = [](int y, int u, int v) { return y < 70 && v >= 160 && u >= 95 && u <= 125; };
    auto accentPixel = [](int, int u, int v) { return u >= 150 && v >= 65 && v <= 115; };
    auto inPane = [&](const DashboardPane::Rect& r, auto test) { return decoder.count(r.x, r.y, r.width, r.height, test); };
    auto accentShare = [&](const DashboardPane::Rect& b) {
        const int x = b.x + std::max(2, b.width / 10);
        const int h = std::max(1, b.height / 2);
        const int n = decoder.count(x, b.y + b.height / 4, 2, h, accentPixel);
        return n < 0 ? -1.0 : n / (2.0 * h);
    };
    auto offerUp = [&]() {
        const double keep = accentShare(pane.offerKeep);
        return accentShare(pane.offerTake) >= 0.9 && keep >= 0.0 && keep < 0.2;
    };
    auto trafficPress = [&](const DashboardPane::Rect& b, const char* what) {
        const QPoint at(b.x + b.width / 2, b.y + b.height / 2);
        const QPoint touchPoint = caps.toTouch(at);
        const oaa::hu::InputChannelHandler::Pointer pointer{touchPoint.x(), touchPoint.y(), 0};
        input->sendTouchIndication(1, &pointer, 0, 0, uptime.elapsed() * 1000);
        input->sendTouchIndication(1, &pointer, 0, 1, uptime.elapsed() * 1000 + 1);
        ++taps;
        out << "receiver_traffic=press " << what << " frame=" << at.x() << "," << at.y() << " touch=" << touchPoint.x()
            << "," << touchPoint.y() << " picture=" << decoder.frames << "\n";
        out.flush();
    };
    qint64 trafficDeadline = 0, trafficSampleAt = 0;
    int trafficRouteStates = -1, trafficOffers = 0;
    int trafficAmberBase = -1, trafficAmberMax = -1, trafficAmberMaxPicture = -1, trafficAmberFirstPicture = -1;
    int trafficClosedBase = -1, trafficClosedMax = -1, trafficClosedMaxPicture = -1, trafficClosedAfter = -1;
    int trafficReroutingAt = -1, trafficResumedAt = -1;
    double trafficFit[2] = {-1.0, -1.0};
    double trafficRefollow = -1.0;
    bool trafficKeepWhole = false, trafficTakeWhole = false, trafficOtherPalette = false;
    QByteArray trafficMapBefore, trafficOfferMap;
    // How long the lab gives each wait: an offer needs a fetch and then the live
    // alternatives' search with the no-go points, which a debug build runs ~30x slower.
    const qint64 TRAFFIC_OFFER_MS = 15 * 60000, TRAFFIC_REROUTE_MS = 10 * 60000;
    // The slow stretch's colour, in pixels of the map pane: a few hundred metres of a 9 px
    // line at street zoom is a thousand or more; a hundred is well clear of noise.
    const int TRAFFIC_AMBER_MIN = 300;
    const int TRAFFIC_CLOSED_MIN = 100;
    // P10 (the weather script, docs/p10-plan.md §8). The header's weather item and the flex
    // tile, judged off the decoded picture. Warm is the glyph's sun (#D98200 by day, #FFC94D at
    // night: U about 60, V 160-176) and an alert's orange (#FF8C00: U 50, V 189): nothing else
    // in the header, an empty tile or a notification row is that colour. The second lab
    // alert's dark red (#8B0000: Y 52, U 108, V 189) is counted apart. Ink is a column of the
    // header with a pixel far from the header's own colour (its commonest luma), which is how
    // the text's right end and the weather item's extent are found without knowing the font.
    auto warmPixel = [](int, int u, int v) { return u < 100 && v > 145; };
    auto darkRedPixel = [](int y, int u, int v) { return y < 75 && v >= 165 && u >= 95 && u <= 125; };
    const Layout weatherContent = caps.layout();
    const QPoint weatherItem(pane.headerWeather.x + pane.headerWeather.width / 2, pane.headerWeather.y + pane.headerWeather.height / 2);
    const QPoint weatherTileCentre(pane.flexTile.x + pane.flexTile.width / 2, pane.flexTile.y + pane.flexTile.height / 2);
    // The glyph's sun in the header item, and the big glyph (or an alert's bar) in the tile.
    const int WEATHER_WARM_HEADER_MIN = 12, WEATHER_WARM_TILE_MIN = 40;
    // A view opening or closing redraws most of the tile; a notification row is a few percent.
    const double WEATHER_TILE_CHANGED = 0.10, WEATHER_NOTIFICATION_MIN = 0.015;
    // The alert's warning mark on the glyph: about 1.4% of the item at 800x480.
    const double WEATHER_MARK_MIN = 0.004;
    const int WEATHER_INK = 40;
    // The app closes an untouched view at 30 s (WeatherRules.VIEW_TIMEOUT_MS); the picture
    // shows it a frame or two later.
    const double WEATHER_TIMEOUT_MIN_S = 29.5, WEATHER_TIMEOUT_MAX_S = 33.0;
    const qint64 WEATHER_NOTIFIER_MAX_MS = 2000;
    struct HeaderInk {
        int bg = -1, textRight = -1, itemLeft = -1, itemRight = -1, beyond = 0, textContrast = 0, itemContrast = 0;
    };
    auto headerInk = [&]() {
        HeaderInk ink;
        const DashboardPane::Rect& box = pane.headerWeather;
        const int x0 = weatherContent.x, x1 = pane.pauseBox.x, w = x1 - x0;
        const QByteArray strip = w > 0 ? decoder.luma(x0, box.y, w, box.height) : QByteArray();
        if (strip.isEmpty())
            return ink;
        int histogram[256] = {0};
        for (char c : strip)
            ++histogram[static_cast<unsigned char>(c)];
        ink.bg = static_cast<int>(std::max_element(histogram, histogram + 256) - histogram);
        const auto* p = reinterpret_cast<const unsigned char*>(strip.constData());
        for (int col = 0; col < w; ++col) {
            int worst = 0;
            for (int row = 0; row < box.height; ++row)
                worst = std::max(worst, std::abs(static_cast<int>(p[row * w + col]) - ink.bg));
            if (worst <= WEATHER_INK)
                continue;
            const int x = x0 + col;
            if (x < box.x) {
                ink.textRight = x;
                ink.textContrast = std::max(ink.textContrast, worst);
            } else if (x < box.x + box.width) {
                if (ink.itemLeft < 0)
                    ink.itemLeft = x;
                ink.itemRight = x;
                ink.itemContrast = std::max(ink.itemContrast, worst);
            } else {
                ++ink.beyond;
            }
        }
        return ink;
    };
    auto weatherNight = [&]() { return !labSensors || labSensors->night(); };
    // One line about the header: where the text ends, where the item's ink is, and how
    // many clear columns lie between the text and the item's box.
    auto weatherHeaderLine = [&](const QString& label) {
        const HeaderInk ink = headerInk();
        const DashboardPane::Rect& box = pane.headerWeather;
        const int clear = ink.textRight < 0 ? box.x - weatherContent.x : box.x - 1 - ink.textRight;
        out << "receiver_weather=header " << label << " picture=" << decoder.frames << " night=" << (weatherNight() ? "true" : "false")
            << " box=" << box.x << "," << box.y << "+" << box.width << "x" << box.height << " pause_x=" << pane.pauseBox.x
            << " bg=" << ink.bg << " text_right=" << ink.textRight << " clear=" << clear << " item=" << ink.itemLeft << ".."
            << ink.itemRight << " beyond=" << ink.beyond << " text_contrast=" << ink.textContrast << " item_contrast="
            << ink.itemContrast << " warm=" << inPane(box, warmPixel) << "\n";
        out.flush();
        return clear;
    };
    auto weatherRender = [&](const QString& what) {
        out << "receiver_weather=render " << what << "-" << (weatherNight() ? "night" : "day") << " picture=" << decoder.frames << "\n";
        out.flush();
    };
    auto weatherPalette = [&]() {
        if (!labSensors)
            return;
        check(labSensors->sendNight(!labSensors->night()), "NIGHT_DATA could not be sent for the other palette");
        out << "receiver_weather=palette night=" << (labSensors->night() ? "true" : "false") << "\n";
        out.flush();
    };
    // The hand-offs with the lab (tools/emulator-lab.sh): the receiver says what it has seen,
    // and waits for what the lab has done; each line is a word and epoch milliseconds.
    auto weatherTell = [&](const QString& word) {
        QFile file(weatherDir + "/weather-receiver.txt");
        if (file.open(QIODevice::Append | QIODevice::Text)) {
            file.write(QString("%1 %2\n").arg(word).arg(QDateTime::currentMSecsSinceEpoch()).toUtf8());
            file.close();
        }
        out << "receiver_weather=told " << word << " picture=" << decoder.frames << "\n";
        out.flush();
    };
    auto weatherHeard = [&](const QString& word) -> qint64 {
        QFile file(weatherDir + "/weather-lab.txt");
        if (!file.open(QIODevice::ReadOnly | QIODevice::Text))
            return -1;
        const QStringList lines = QString::fromUtf8(file.readAll()).split('\n');
        for (const QString& line : lines) {
            const QStringList parts = line.trimmed().split(' ');
            if (!parts.isEmpty() && parts[0] == word)
                return parts.size() > 1 ? parts[1].toLongLong() : 0;
        }
        return -1;
    };
    auto weatherPress = [&](QPoint at, const char* what) {
        const QPoint touchPoint = caps.toTouch(at);
        const oaa::hu::InputChannelHandler::Pointer pointer{touchPoint.x(), touchPoint.y(), 0};
        input->sendTouchIndication(1, &pointer, 0, 0, uptime.elapsed() * 1000);
        input->sendTouchIndication(1, &pointer, 0, 1, uptime.elapsed() * 1000 + 1);
        ++taps;
        out << "receiver_weather=press " << what << " frame=" << at.x() << "," << at.y() << " touch=" << touchPoint.x() << ","
            << touchPoint.y() << " picture=" << decoder.frames << "\n";
        out.flush();
    };
    // Waits for [ok] or the deadline; then says and checks it. False while still waiting.
    QStringList weatherResults;
    auto weatherWait = [&](bool ok, const QString& what, const QString& detail) {
        if (!ok && uptime.elapsed() < stepDeadline)
            return false;
        out << "receiver_weather=" << what << " " << (ok ? "ok" : "FAILED") << " picture=" << decoder.frames << " " << detail << "\n";
        out.flush();
        weatherResults << QString("%1=%2").arg(what, ok ? "ok" : "failed");
        check(ok, QString("weather: %1 (%2)").arg(what, detail));
        return true;
    };
    // The show, tap and notifier views carry the fixture's partly cloudy glyph; the stale
    // one is cloudy and the unsupported one a status line, so only the tile's change counts.
    const bool weatherWarmView = weatherMode == "show" || weatherMode == "tap" || weatherMode == "notifier";
    // A view that is only a status line (unsupported) changes about 6% of the tile.
    const double weatherViewChanged = weatherMode == "unsupported" ? 0.03 : WEATHER_TILE_CHANGED;
    QByteArray weatherTileRef, weatherTileOpen, weatherHeaderRef, weatherNotificationTile;
    qint64 weatherSettleAt = -1, weatherPressedAt = -1, weatherNotifiedAt = -1;
    double weatherTimeoutS = -1.0, weatherHeaderMark = -1.0;
    qint64 weatherNotifierMs = -1;
    int weatherOpens = 0;
    auto weatherTileDetail = [&](const QByteArray& from) {
        return QString("changed=%1 warm=%2 dark_red=%3")
            .arg(changedFraction(from, paneLuma(pane.flexTile), TRACK_LUMA_THRESHOLD), 0, 'f', 3)
            .arg(inPane(pane.flexTile, warmPixel))
            .arg(inPane(pane.flexTile, darkRedPixel));
    };
    auto weatherIsOpen = [&]() {
        return changedFraction(weatherTileRef, paneLuma(pane.flexTile), TRACK_LUMA_THRESHOLD) >= weatherViewChanged
               && (!weatherWarmView || inPane(pane.flexTile, warmPixel) >= WEATHER_WARM_TILE_MIN);
    };
    auto weatherIsClosed = [&]() {
        return changedFraction(weatherTileOpen, paneLuma(pane.flexTile), TRACK_LUMA_THRESHOLD) >= weatherViewChanged
               && (!weatherWarmView || inPane(pane.flexTile, warmPixel) < WEATHER_WARM_TILE_MIN / 4);
    };
    if (projection && script == "weather") {
        out << "receiver_weather=layout mode=" << weatherMode << " item=" << pane.headerWeather.x << "," << pane.headerWeather.y
            << "+" << pane.headerWeather.width << "x" << pane.headerWeather.height << " pause=" << pane.pauseBox.x << ","
            << pane.pauseBox.y << "+" << pane.pauseBox.width << "x" << pane.pauseBox.height << " flex=" << pane.flexTile.x << ","
            << pane.flexTile.y << "+" << pane.flexTile.width << "x" << pane.flexTile.height << "\n";
        out.flush();
    }
    if (projection && script != "none") {
        QObject::connect(&scriptTimer, &QTimer::timeout, &app, [&]() {
            if (!video || !input || decoder.frames == 0)
                return; // wait for the first decoded picture
            const Layout layout = caps.layout();
            int base = 0;
            if (script == "focus")
                base = 100;
            else if (script == "stall")
                base = 200;
            else if (script == "transport")
                base = 300;
            else if (script == "audio")
                base = 400;
            else if (script == "track")
                base = 500;
            else if (script == "navigation")
                base = 600;
            else if (script == "guidance")
                base = 700;
            else if (script == "daynight")
                base = 800;
            else if (script == "controls")
                base = 900;
            else if (script == "endroute")
                base = 1000;
            else if (script == "calls")
                base = 1100;
            else if (script == "speech")
                base = 1200;
            else if (script == "voice")
                base = 1300;
            else if (script == "traffic")
                base = 1400;
            else if (script == "weather")
                base = 1500;
            else if (script == "congestion")
                base = 1600;
            switch (step + base) {
            case 0:
            case 2:
            case 4:
            case 6: {
                const int index = step / 2;
                const QPoint center = layout.center(index);
                const QPoint patch = layout.sample(index);
                before = decoder.sample(patch.x(), patch.y());
                const QPoint touchPoint = caps.toTouch(center);
                const oaa::hu::InputChannelHandler::Pointer pointer{touchPoint.x(), touchPoint.y(), 0};
                input->sendTouchIndication(1, &pointer, 0, 0, uptime.elapsed() * 1000);
                input->sendTouchIndication(1, &pointer, 0, 1, uptime.elapsed() * 1000 + 1);
                ++taps;
                out << "receiver_tap=" << index << " frame=" << center.x() << "," << center.y()
                    << " touch=" << touchPoint.x() << "," << touchPoint.y()
                    << " space=" << caps.touchSpace << "\n";
                out.flush();
                stepFrames = decoder.frames;
                // The source has to draw and encode the change, and the decoder to
                // deliver it; poll until it appears or this deadline passes.
                stepDeadline = uptime.elapsed() + TAP_DEADLINE_MS;
                ++step;
                break;
            }
            case 1:
            case 3:
            case 5:
            case 7: {
                const int index = step / 2;
                const QPoint patch = layout.sample(index);
                const Yuv after = decoder.sample(patch.x(), patch.y());
                const Yuv want = buttonColor(index);
                const bool close = std::abs(after.y - want.y) <= tolerance
                    && std::abs(after.u - want.u) <= tolerance
                    && std::abs(after.v - want.v) <= tolerance;
                if (!close && uptime.elapsed() < stepDeadline)
                    return; // the change has not been decoded yet
                check(close,
                      QString("button %1 decoded as %2,%3,%4, expected %5,%6,%7")
                          .arg(index)
                          .arg(after.y)
                          .arg(after.u)
                          .arg(after.v)
                          .arg(want.y)
                          .arg(want.u)
                          .arg(want.v));
                check(after.y != before.y || after.u != before.u || after.v != before.v,
                      QString("button %1 did not change after the tap").arg(index));
                out << "receiver_tap_result=" << index << " before=" << before.y << "," << before.u
                    << "," << before.v << " after=" << after.y << "," << after.u << "," << after.v
                    << "\n";
                out.flush();
                ++step;
                break;
            }
            case 8: {
                // A head-unit button press, then finish.
                input->sendButtonEvent(84, true, uptime.elapsed() * 1000);
                input->sendButtonEvent(84, false, uptime.elapsed() * 1000 + 1);
                ++keys;
                ++step;
                stepFrames = decoder.frames;
                break;
            }
            case 100: {
                // Focus loss: the source must stop sending until focus returns.
                video->requestVideoFocus(false);
                stepFrames = decoder.frames;
                out << "receiver_focus=none\n";
                out.flush();
                ++step;
                break;
            }
            case 101: {
                static int quiet = 0;
                if (++quiet < 10)
                    return; // about a second of no projected focus
                check(decoder.frames <= stepFrames + 2,
                      QString("%1 pictures arrived after focus was dropped")
                          .arg(decoder.frames - stepFrames));
                video->requestVideoFocus(true);
                stepFrames = decoder.frames;
                out << "receiver_focus=projected\n";
                out.flush();
                ++step;
                break;
            }
            case 102: {
                if (decoder.frames < stepFrames + 5)
                    return;
                check(decoder.errors == 0, "decode errors after focus returned");
                ++step;
                break;
            }
            case 103: {
                // A regain taken straight back: focus lost, then handed back and lost again
                // in one breath, as a head unit flicking between its own screens can. The
                // source's encoder rebuild for the regain must not leave it drawing once the
                // second loss has landed; the app side counts what it drew for nothing
                // (projection_dropped_not_streaming, tools/emulator-lab.sh).
                video->requestVideoFocus(false);
                stepFrames = decoder.frames;
                out << "receiver_focus=none\n";
                out.flush();
                ++step;
                break;
            }
            case 104: {
                static int quiet = 0;
                if (++quiet < 10)
                    return;
                check(decoder.frames <= stepFrames + 2,
                      QString("%1 pictures arrived after focus was dropped again")
                          .arg(decoder.frames - stepFrames));
                video->requestVideoFocus(true);
                video->requestVideoFocus(false);
                stepFrames = decoder.frames;
                out << "receiver_focus=flicker\n";
                out.flush();
                ++step;
                break;
            }
            case 105: {
                static int quiet = 0;
                if (++quiet < 120)
                    return; // twelve seconds with the screen still the head unit's, for
                            // the lab to read the source's own report twice meanwhile
                check(decoder.frames <= stepFrames + 2,
                      QString("%1 pictures arrived after a regain taken straight back")
                          .arg(decoder.frames - stepFrames));
                video->requestVideoFocus(true);
                stepFrames = decoder.frames;
                out << "receiver_focus=projected\n";
                out.flush();
                ++step;
                break;
            }
            case 106: {
                if (decoder.frames < stepFrames + 5)
                    return;
                check(decoder.errors == 0, "decode errors after focus returned after the flicker");
                ++step;
                break;
            }
            case 200: {
                // Withhold acknowledgements: the source's window fills and it recovers
                // at an IDR once they are released.
                video->setStalling(true);
                stepFrames = decoder.frames;
                out << "receiver_acks=withheld\n";
                out.flush();
                ++step;
                break;
            }
            case 201: {
                static int stalled = 0;
                if (++stalled < 20)
                    return; // about two seconds
                video->setStalling(false);
                const int released = video->releaseAcks();
                out << "receiver_acks=released count=" << released << "\n";
                out.flush();
                stepFrames = decoder.frames;
                ++step;
                break;
            }
            case 202: {
                if (decoder.frames < stepFrames + 5)
                    return;
                check(decoder.errors == 0, "decode errors after the acknowledgement stall");
                ++step;
                break;
            }
            // P5.1. A transport button is only proved to have arrived if something the
            // receiver can see changes: the playback state on the now-playing channel and
            // the swatch in the decoded picture must both follow the press.
            case 300: {
                if (statusMessages == 0)
                    return; // the source has not published yet
                check(playbackState == 2,
                      QString("expected PLAYING before the first press, got %1")
                          .arg(playbackState));
                before = decoder.sample(layout.transportSample().x(),
                                        layout.transportSample().y());
                stepState = playbackState;
                input->sendButtonEvent(85, true, uptime.elapsed() * 1000); // PLAY_PAUSE
                input->sendButtonEvent(85, false, uptime.elapsed() * 1000 + 1);
                ++keys;
                out << "receiver_transport=play-pause\n";
                out.flush();
                stepDeadline = uptime.elapsed() + TAP_DEADLINE_MS;
                ++step;
                break;
            }
            case 301: {
                const QPoint patch = layout.transportSample();
                const Yuv after = decoder.sample(patch.x(), patch.y());
                const Yuv want = transportColor(playbackState);
                const bool changed = playbackState != stepState;
                const bool close = std::abs(after.y - want.y) <= tolerance
                    && std::abs(after.u - want.u) <= tolerance
                    && std::abs(after.v - want.v) <= tolerance;
                if ((!changed || !close) && uptime.elapsed() < stepDeadline)
                    return;
                check(changed,
                      QString("playback state stayed %1 after play-pause").arg(stepState));
                check(playbackState == 3,
                      QString("expected PAUSED after play-pause, got %1").arg(playbackState));
                check(close,
                      QString("now-playing swatch decoded as %1,%2,%3, expected %4,%5,%6")
                          .arg(after.y).arg(after.u).arg(after.v)
                          .arg(want.y).arg(want.u).arg(want.v));
                check(after.y != before.y || after.u != before.u || after.v != before.v,
                      "the now-playing swatch did not change after play-pause");
                out << "receiver_transport_result=play-pause state=" << playbackState << "\n";
                out.flush();
                ++step;
                break;
            }
            case 302: {
                check(metadataMessages > 0, "no metadata before the track change");
                stepTitle = trackTitle;
                input->sendButtonEvent(87, true, uptime.elapsed() * 1000); // MEDIA_NEXT
                input->sendButtonEvent(87, false, uptime.elapsed() * 1000 + 1);
                ++keys;
                out << "receiver_transport=next\n";
                out.flush();
                stepDeadline = uptime.elapsed() + TAP_DEADLINE_MS;
                ++step;
                break;
            }
            case 303: {
                if (trackTitle == stepTitle && uptime.elapsed() < stepDeadline)
                    return;
                check(trackTitle != stepTitle,
                      QString("the track stayed \"%1\" after next").arg(stepTitle));
                // The P5.1 open risk: album art is unbounded bytes on a 16 KiB-fragment
                // transport, so this slice must send none.
                check(albumArtBytes == 0,
                      QString("%1 bytes of album art were sent").arg(albumArtBytes));
                out << "receiver_transport_result=next title=" << trackTitle << "\n";
                out.flush();
                ++step;
                break;
            }
            // P5.3. Audio is proved to have arrived only if the samples are the ones
            // the source says it is sending: the receiver measures the two fundamentals
            // in the PCM it was handed rather than trusting a buffer count.
            case 400: {
                if (!audio || audio->buffers < TONE_BUFFERS)
                    return; // wait for enough audio to measure
                check(audioStarted, "no audio start indication");
                check(audioConfigIndex == 0,
                      QString("audio started at configuration index %1").arg(audioConfigIndex));
                const QByteArray pcm = audio->latest;
                const int channels = caps.audioChannels;
                const double left = toneMagnitude(pcm, channels, 0, caps.audioSampleRate,
                                                  LAB_TONE_LEFT_HZ);
                const double leftWrong = toneMagnitude(pcm, channels, 0, caps.audioSampleRate,
                                                       LAB_TONE_RIGHT_HZ);
                out << "receiver_audio_tone=left_440=" << left << " left_880=" << leftWrong;
                check(left > TONE_FLOOR,
                      QString("440 Hz magnitude in the left channel was %1").arg(left));
                check(left > leftWrong * 2.0,
                      QString("the left channel carried 880 Hz (%1) as strongly as 440 Hz (%2)")
                          .arg(leftWrong).arg(left));
                if (channels > 1) {
                    const double right = toneMagnitude(pcm, channels, 1, caps.audioSampleRate,
                                                       LAB_TONE_RIGHT_HZ);
                    const double rightWrong = toneMagnitude(pcm, channels, 1,
                                                            caps.audioSampleRate,
                                                            LAB_TONE_LEFT_HZ);
                    out << " right_880=" << right << " right_440=" << rightWrong;
                    check(right > TONE_FLOOR,
                          QString("880 Hz magnitude in the right channel was %1").arg(right));
                    // A swapped pair is the failure this catches: both channels would
                    // still carry a tone, and only the assignment would be wrong.
                    check(right > rightWrong * 2.0,
                          QString("the channels look swapped: right 440 Hz %1 against 880 Hz %2")
                              .arg(rightWrong).arg(right));
                }
                out << "\n";
                out.flush();
                ++step;
                break;
            }
            // P6.1. A moving position source, and the one question it exists to ask:
            // does the projected map follow the vehicle? The answer has to come out of
            // the decoded picture, because every phone-side counter here would read the
            // same whether the pane rasterised a new view or re-composited the old
            // texture. The static half of the dashboard is measured at the same time,
            // so "the map moved" cannot be satisfied by a picture where everything did.
            case 500: {
                // Wait for the parked map to go quiet rather than for a fixed number of
                // frames. Tiles arrive on VTM worker threads and change the pane on
                // their own, so a baseline taken while they land would measure the
                // loading; and "a stationary vehicle stops redrawing" is itself the
                // claim P5.5 made from counters and never checked in a picture.
                if (!labSensors || !trackPlayer || uptime.elapsed() < stepDeadline)
                    return;
                stepDeadline = uptime.elapsed() + TRACK_SAMPLE_MS;
                const QByteArray map = paneLuma(pane.map);
                if (map.isEmpty()) {
                    check(false, "the map pane is outside the decoded picture");
                    ++step;
                    break;
                }
                const double change = changedFraction(mapBefore, map, TRACK_LUMA_THRESHOLD);
                mapBefore = map;
                ++trackSettleTries;
                if (change < 0.0)
                    return; // the first sample has nothing to compare against
                if (change >= TRACK_STATIC_MAX && trackSettleTries < TRACK_SETTLE_TRIES)
                    return;
                out << "receiver_track=parked map_changed=" << change
                    << " settle_s=" << trackSettleTries << " fixes=" << labSensors->fixes
                    << " frames=" << decoder.frames << "\n";
                out.flush();
                check(change < TRACK_STATIC_MAX,
                      QString("a parked map was still changing %1 of the pane after %2 s")
                          .arg(change)
                          .arg(trackSettleTries));
                if (!poiKinds.isEmpty()) {
                    // P11.5: the markers first, parked, in both palettes; then the drive.
                    poiStart = uptime.elapsed();
                    step = 10;
                    break;
                }
                if (!poiAheadDone) {
                    // P11.5a: search ahead, parked on a followed route; then the drive.
                    poiAheadAt = uptime.elapsed();
                    step = 20;
                    break;
                }
                tilesBefore = paneLuma(pane.tiles);
                trackPlayer->depart();
                trackMark = trackPlayer->travelled();
                out << "receiver_track=departed\n";
                out.flush();
                ++step;
                break;
            }
            case 501:
            case 502: {
                // Two intervals rather than one. A map that redraws once and then goes
                // quiet is not a hypothetical - it is P5.5's third defect, where tiles
                // finishing on VTM worker threads never woke the pane again - and a
                // single before-and-after pair cannot tell it apart from one that keeps
                // following.
                if (trackPlayer->travelled() < trackMark + TRACK_STEP_METRES)
                    return;
                const QByteArray map = paneLuma(pane.map);
                const QByteArray tiles = paneLuma(pane.tiles);
                const double moved = changedFraction(mapBefore, map, TRACK_LUMA_THRESHOLD);
                const double still = changedFraction(tilesBefore, tiles, TRACK_LUMA_THRESHOLD);
                out << "receiver_track_moved=" << step << " metres="
                    << qRound(trackPlayer->travelled() - trackMark)
                    << " map_changed=" << moved << " tiles_changed=" << still
                    << " speed_ms=" << trackPlayer->speed()
                    << " bearing=" << trackPlayer->heading()
                    << " fixes=" << labSensors->fixes << "\n";
                out.flush();
                check(moved > TRACK_MOVED_MIN,
                      QString("the map pane changed %1 of itself over %2 m of driving")
                          .arg(moved)
                          .arg(qRound(trackPlayer->travelled() - trackMark)));
                check(still >= 0.0 && still < TRACK_STATIC_MAX,
                      QString("the static tiles changed %1 of themselves").arg(still));
                check(moved > still * 4.0,
                      QString("the map (%1) changed no more than the static half (%2)")
                          .arg(moved)
                          .arg(still));
                mapBefore = map;
                tilesBefore = tiles;
                trackMark = trackPlayer->travelled();
                ++step;
                break;
            }
            // P11.5. The POI buttons were lit by the lab before the projection started, so by
            // now the parked map should carry their markers: discs in each kind's own colour
            // (PoiRules.colour), counted in the pane below the button row, whose colour dots
            // are not markers. Then the other palette, since the markers must read on both
            // grounds, a render of each, and back, before the vehicle departs as ever.
            case 510:
            case 511: {
                if (!labSensors || uptime.elapsed() < stepDeadline)
                    return;
                stepDeadline = uptime.elapsed() + POI_SAMPLE_MS;
                // The first palette's tiles may still be loading over a parked map that has
                // stopped changing (a 575 MB Texas map at night drew bare ground for seconds).
                if (step == 10 && uptime.elapsed() - poiStart < POI_SETTLE_MS)
                    return;
                const PoiCounts counts = poiCounts();
                bool all = true;
                for (int i = 0; i < 3; ++i) {
                    if (poiKinds.contains(POI_NAMES[i]) && counts.kind[i] < POI_MIN)
                        all = false;
                }
                const bool timedOut = uptime.elapsed() - poiStart > POI_WAIT_MS;
                if (!all && !timedOut)
                    return;
                const QString palette = labSensors->night() ? "night" : "day";
                out << "receiver_poi=markers " << palette << " picture=" << decoder.frames << " gas=" << counts.kind[0]
                    << " food=" << counts.kind[1] << " shop=" << counts.kind[2] << " lit=" << poiKinds.join(',')
                    << " every_lit_kind=" << (all ? "true" : "false") << " waited_ms=" << (uptime.elapsed() - poiStart) << "\n";
                int total = 0;
                for (int i = 0; i < 3; ++i) {
                    if (poiKinds.contains(POI_NAMES[i]))
                        total += counts.kind[i];
                }
                check(total >= POI_MIN, QString("no POI markers in the %1 map pane: gas %2 food %3 shop %4")
                                            .arg(palette).arg(counts.kind[0]).arg(counts.kind[1]).arg(counts.kind[2]));
                out << "receiver_poi=render poi-" << palette << " picture=" << decoder.frames << "\n";
                out.flush();
                check(labSensors->sendNight(!labSensors->night()), "NIGHT_DATA could not be sent for the other palette");
                // A theme switch holds the old picture until the new tiles are in.
                stepDeadline = uptime.elapsed() + POI_PALETTE_MS;
                poiStart = stepDeadline;
                ++step;
                break;
            }
            case 512: {
                // A tap on a marker opens its card in the flex tile (P11.5): the first window of
                // a lit kind's colour clear of everything drawn over the map is a marker's disc.
                if (uptime.elapsed() < stepDeadline)
                    return;
                QPoint at;
                QString kind;
                for (int i = 0; i < 3 && kind.isEmpty(); ++i) {
                    if (poiKinds.contains(POI_NAMES[i]) && poiFind(i, at))
                        kind = POI_NAMES[i];
                }
                check(!kind.isEmpty(), "no marker found to tap");
                if (kind.isEmpty()) {
                    step = 15;
                    break;
                }
                poiTileBefore = paneLuma(pane.flexTile);
                poiPress(at, QString("marker-") + kind);
                stepDeadline = uptime.elapsed() + 2500;
                ++step;
                break;
            }
            case 513: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                const double opened = changedFraction(poiTileBefore, paneLuma(pane.flexTile), TRACK_LUMA_THRESHOLD);
                out << "receiver_poi=card changed=" << opened << " picture=" << decoder.frames << "\n";
                check(opened >= 0.10, QString("the flex tile changed only %1 after a tap on a marker").arg(opened));
                out << "receiver_poi=render poi-card-" << (labSensors && labSensors->night() ? "night" : "day")
                    << " picture=" << decoder.frames << "\n";
                out.flush();
                // DashboardLayout.poiCardButton(1), Close, at the tile's bottom right.
                const DashboardPane::Rect& tile = pane.flexTile;
                const int margin = std::max(2, caps.layout().width / 96);
                const int bubble = std::max(24, std::min(pane.mapFull.width, pane.mapFull.height) / 8);
                const int h = std::min(bubble, tile.height / 4);
                const int w = std::max(0, (tile.width - 3 * margin) / 2);
                poiPress(QPoint(tile.x + margin + w + margin + w / 2, tile.y + tile.height - margin - h / 2), "close");
                stepDeadline = uptime.elapsed() + 2000;
                ++step;
                break;
            }
            case 514: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                const double back = changedFraction(poiTileBefore, paneLuma(pane.flexTile), TRACK_LUMA_THRESHOLD);
                out << "receiver_poi=closed changed_from_before=" << back << " picture=" << decoder.frames << "\n";
                out.flush();
                check(back >= 0.0 && back < 0.05, QString("the flex tile is %1 away from before the card after Close").arg(back));
                ++step;
                break;
            }
            case 515: {
                if (!poiAheadDone) {
                    poiAheadAt = uptime.elapsed();
                    step = 20;
                    break;
                }
                mapBefore = paneLuma(pane.map);
                tilesBefore = paneLuma(pane.tiles);
                trackPlayer->depart();
                trackMark = trackPlayer->travelled();
                out << "receiver_track=departed\n";
                out.flush();
                step = 1;
                break;
            }
            // P11.5a. Search ahead. The lab script says when the route up I-35 is being
            // followed; then this kind's button is pressed where it sits during a route, a
            // margin under the turn card (DashboardLayout.poiButtonsTop and poiButton): round
            // map bubbles down the pane's left edge, a margin in and apart, gas, food, shop.
            case 520: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                stepDeadline = uptime.elapsed() + POI_AHEAD_SAMPLE_MS;
                if (!QFile::exists(POI_AHEAD_GO)) {
                    if (uptime.elapsed() - poiAheadAt < POI_AHEAD_GO_MS)
                        return;
                    check(false, "the lab script never said a route was being followed");
                    poiAheadDone = true;
                    step = 15;
                    break;
                }
                const DashboardPane::Rect& full = pane.mapFull;
                const int margin = std::max(2, caps.layout().width / 96);
                const int bubble = std::max(24, std::min(full.width, full.height) / 8);
                const int top = pane.turnCard.y + pane.turnCard.height + margin;
                const int index = poiAhead == "gas" ? 0 : poiAhead == "food" ? 1 : 2;
                poiTileBefore = paneLuma(pane.flexTile);
                poiPress(QPoint(full.x + margin + bubble / 2, top + index * (bubble + margin) + bubble / 2), QString("button-") + poiAhead);
                poiAheadAt = uptime.elapsed();
                ++step;
                break;
            }
            case 521: {
                // The card replaces whatever the flex tile showed: a tenth of it changed, as for a
                // marker's card. The search reads the corridor and routes ten short routes in a
                // debug build first, so this waits minutes rather than seconds.
                if (uptime.elapsed() < stepDeadline)
                    return;
                stepDeadline = uptime.elapsed() + POI_AHEAD_SAMPLE_MS;
                const double changed = changedFraction(poiTileBefore, paneLuma(pane.flexTile), TRACK_LUMA_THRESHOLD);
                const qint64 waited = uptime.elapsed() - poiAheadAt;
                if (changed < 0.10 && waited < POI_AHEAD_CARD_MS)
                    return;
                out << "receiver_poi_ahead=card changed=" << changed << " waited_ms=" << waited << " picture=" << decoder.frames << "\n";
                check(changed >= 0.10, QString("the flex tile changed only %1 in %2 s after the %3 press").arg(changed).arg(waited / 1000).arg(poiAhead));
                if (changed < 0.10) {
                    poiAheadDone = true;
                    step = 15;
                    break;
                }
                // A second for the rows to be drawn whole before the render.
                stepDeadline = uptime.elapsed() + 1000;
                ++step;
                break;
            }
            case 522: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                out << "receiver_poi=render poi-ahead-" << (labSensors && labSensors->night() ? "night" : "day")
                    << " picture=" << decoder.frames << "\n";
                // DashboardLayout.poiCardButton(0), Add stop, at the tile's bottom left.
                const DashboardPane::Rect& tile = pane.flexTile;
                const int margin = std::max(2, caps.layout().width / 96);
                const int bubble = std::max(24, std::min(pane.mapFull.width, pane.mapFull.height) / 8);
                const int h = std::min(bubble, tile.height / 4);
                const int w = std::max(0, (tile.width - 3 * margin) / 2);
                poiPress(QPoint(tile.x + margin + w / 2, tile.y + tile.height - margin - h / 2), "add-stop");
                stepDeadline = uptime.elapsed() + 3000;
                ++step;
                break;
            }
            case 523: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                out << "receiver_poi_ahead=added picture=" << decoder.frames << "\n";
                out.flush();
                poiAheadDone = true;
                step = 15;
                break;
            }
            // P6.2. Two steps, both of them waits: the sequence has to start at all, and
            // then it has to keep coming at no more than the advertised rate. Neither is
            // a picture check — the cluster is the head unit's own display and nothing
            // about it reaches the projected stream — so this is the receiver reading its
            // own decode of what the source sent.
            case 600: {
                if (!navigation)
                    return;
                // Expecting silence is still a measurement: watch for long enough that a
                // source which was going to send would have, then say so.
                if (!caps.navigationExpectSequence) {
                    if (!navSilence.isValid())
                        navSilence.start();
                    if (navSilence.elapsed() < NAV_SILENCE_MS)
                        return;
                    out << "receiver_navigation_silence=observed_ms=" << navSilence.elapsed()
                        << " states=" << navigation->states
                        << " notifications=" << navigation->notifications
                        << " distances=" << navigation->distances << "\n";
                    out.flush();
                    step += 2;
                    break;
                }
                if (navigation->states == 0 || navigation->notifications == 0
                    || navigation->distances == 0)
                    return;
                out << "receiver_navigation_active=states=" << navigation->states
                    << " cluster_announced=" << (navigation->clusterAnnounced ? "true" : "false")
                    << " maneuver=" << navManeuver << " step_distance=" << navStepDistance
                    << " unit=" << navStepUnit << " value=" << navStepValue
                    << " to_step_s=" << navTimeToStep << " to_arrival_s=" << navTimeToArrival
                    << "\n";
                out.flush();
                ++step;
                break;
            }
            case 601: {
                // Three updates, so there are at least two gaps to take the smallest of.
                if (!navigation || navigation->updates < 3)
                    return;
                out << "receiver_navigation_rate=updates=" << navigation->updates
                    << " smallest_gap_ms=" << navigation->smallestGapMs
                    << " advertised_floor_ms=" << caps.navigationIntervalMs << "\n";
                out.flush();
                // The head unit's own clock, not the source's account of it. A little
                // slack for scheduling on a loaded emulator host; the failure this
                // catches is a source ignoring the floor entirely, which is off by a
                // factor, not by a few milliseconds.
                check(navigation->smallestGapMs >= caps.navigationIntervalMs * 9 / 10,
                      QString("updates arrived %1 ms apart against an advertised floor of %2")
                          .arg(navigation->smallestGapMs)
                          .arg(caps.navigationIntervalMs));
                ++step;
                break;
            }
            // P6.5. The vehicle waits at the kerb until the source has a route on the
            // cluster - the destination is the lab's to send, and the route is computed
            // from the parked position - then drives the whole of --track-file, and the
            // run ends once the source has said the drive arrived or has had its chance.
            case 700: {
                if (!navigation || !trackPlayer || !navStates.contains("1"))
                    return;
                trackPlayer->depart();
                out << "receiver_guidance=departed maneuver=" << navManeuver
                    << " step_distance=" << navStepDistance << " path_m=" << qRound(trackPlayer->pathLength())
                    << " cruise_ms=" << trackSpeed << "\n";
                out.flush();
                ++step;
                break;
            }
            case 701: {
                if (!trackPlayer->finished())
                    return;
                out << "receiver_guidance=stopped travelled_m=" << qRound(trackPlayer->travelled())
                    << " states=" << navStates.join(',') << "\n";
                out.flush();
                guideArrival.start();
                ++step;
                break;
            }
            case 702: {
                // Arrival is the source's call: parked at the end, it should say so within
                // a few fixes. A source that never does is a failure the checks name.
                if (navStates.last() != "2" && guideArrival.elapsed() < GUIDE_ARRIVAL_MS)
                    return;
                ++step;
                break;
            }
            // P7.6. Day, then night, day and night again, each flip watched picture by
            // picture (see FlipWatch). The second day and the second night are compared
            // with the first: a theme switch that left a tile half-themed or empty would
            // make them differ, and would otherwise pass as "the pane changed".
            case 800: {
                // Parked, and settled in the day theme the subscription asked for, before
                // anything flips; the same wait as the track script's, for the same reason.
                if (!labSensors || !labSensors->requested.contains(10) || uptime.elapsed() < stepDeadline)
                    return;
                stepDeadline = uptime.elapsed() + TRACK_SAMPLE_MS;
                const QByteArray map = paneLuma(pane.map);
                if (map.isEmpty()) {
                    check(false, "the map pane is outside the decoded picture");
                    step = 4;
                    break;
                }
                const double change = changedFraction(mapBefore, map, TRACK_LUMA_THRESHOLD);
                mapBefore = map;
                ++trackSettleTries;
                if (change < 0.0)
                    return;
                if (change >= TRACK_STATIC_MAX && trackSettleTries < TRACK_SETTLE_TRIES)
                    return;
                double mean = 0.0, spread = 0.0;
                lumaStats(map, mean, spread);
                out << "receiver_daynight=day settle_s=" << trackSettleTries << " map_changed=" << change
                    << " pane_mean=" << qRound(mean) << " pane_spread=" << qRound(spread)
                    << " night_events=" << labSensors->nightEvents << "\n";
                out.flush();
                check(change < TRACK_STATIC_MAX,
                      QString("a parked map was still changing %1 of the pane after %2 s")
                          .arg(change).arg(trackSettleTries));
                check(spread >= FLIP_BLANK_SD, "the day map pane is blank before any flip");
                dayFirst = map;
                startFlip(true);
                ++step;
                break;
            }
            case 801:
            case 802:
            case 803: {
                if (!flip.done)
                    return;
                finishFlip();
                if (step == 1) {
                    nightFirst = flip.settled;
                    startFlip(false);
                } else if (step == 2) {
                    const double repeat = changedFraction(dayFirst, flip.settled, TRACK_LUMA_THRESHOLD);
                    out << "receiver_daynight_repeat=day changed=" << repeat << "\n";
                    check(repeat >= 0.0 && repeat < FLIP_REPEAT_MAX,
                          QString("the second day picture differs from the first by %1").arg(repeat));
                    startFlip(true);
                } else {
                    const double repeat = changedFraction(nightFirst, flip.settled, TRACK_LUMA_THRESHOLD);
                    out << "receiver_daynight_repeat=night changed=" << repeat
                        << " night_events=" << labSensors->nightEvents << "\n";
                    check(repeat >= 0.0 && repeat < FLIP_REPEAT_MAX,
                          QString("the second night picture differs from the first by %1").arg(repeat));
                }
                out.flush();
                ++step;
                break;
            }
            // P7.2, P7.3. Settle, press, watch; once per entry of controlPresses.
            case 900: {
                // The parked map has to be still before a press, or a tile arriving late
                // would pass for the zoom.
                if (!labSensors || !labSensors->requested.contains(10) || uptime.elapsed() < stepDeadline)
                    return; // and the head unit has said night, which the last press relies on
                stepDeadline = uptime.elapsed() + TRACK_SAMPLE_MS;
                const QByteArray map = paneLuma(pane.map);
                const double change = changedFraction(mapBefore, map, TRACK_LUMA_THRESHOLD);
                mapBefore = map;
                ++controlSettleTries;
                if (change < 0.0 || (change >= TRACK_STATIC_MAX && controlSettleTries < TRACK_SETTLE_TRIES))
                    return;
                const ControlPress& press = controlPresses[controlIndex];
                controlBefore = paneLuma(press.watch);
                double spread = 0.0;
                lumaStats(controlBefore, controlBeforeMean, spread);
                const QPoint touchPoint = caps.toTouch(press.at);
                const oaa::hu::InputChannelHandler::Pointer pointer{touchPoint.x(), touchPoint.y(), 0};
                input->sendTouchIndication(1, &pointer, 0, 0, uptime.elapsed() * 1000);
                input->sendTouchIndication(1, &pointer, 0, 1, uptime.elapsed() * 1000 + 1);
                ++taps;
                out << "receiver_control=" << press.name << " frame=" << press.at.x() << ","
                    << press.at.y() << " touch=" << touchPoint.x() << "," << touchPoint.y()
                    << " settle_s=" << controlSettleTries << " map_changed=" << change << "\n";
                out.flush();
                controlSettleTries = 0;
                stepDeadline = uptime.elapsed() + TAP_DEADLINE_MS;
                ++step;
                break;
            }
            case 901: {
                const ControlPress& press = controlPresses[controlIndex];
                const QByteArray after = paneLuma(press.watch);
                const double changed = changedFraction(controlBefore, after, TRACK_LUMA_THRESHOLD);
                double mean = 0.0, spread = 0.0;
                lumaStats(after, mean, spread);
                const bool lighter = std::string(press.name) != "day-night"
                    || mean - controlBeforeMean >= FLIP_LUMA_SHIFT;
                if ((changed < press.minChanged || !lighter) && uptime.elapsed() < stepDeadline)
                    return;
                out << "receiver_control_result=" << press.name << " changed=" << changed
                    << " mean=" << qRound(controlBeforeMean) << "->" << qRound(mean) << "\n";
                out.flush();
                check(changed >= press.minChanged,
                      QString("%1 changed only %2 of what it should").arg(press.name).arg(changed));
                check(lighter, QString("day/night left the header at luma %1 from %2")
                                   .arg(qRound(mean)).arg(qRound(controlBeforeMean)));
                ++controlIndex;
                mapBefore.clear();
                stepDeadline = uptime.elapsed() + TRACK_SAMPLE_MS;
                // Back to settling for the next press, or on past the last one.
                step = controlIndex < controlPresses.size() ? 0 : 2;
                break;
            }
            // P7.4. Parked with a route on the cluster, End route is pressed on the trip panel:
            // the cluster must go INACTIVE and hear no route after it, the trip panel must
            // give the tile back and the turn card leave the map. Then P7.5's first Resume
            // row is pressed; what it does depends on the emulator's apps, so it is logged
            // and the app's own counters (tools/emulator-lab.sh) are what is checked.
            case 1000: {
                if (!navigation || !navStates.contains("1"))
                    return; // no route on the cluster yet
                if (stepDeadline == 0) {
                    // Three seconds for the panel and the card to be drawn and settle.
                    stepDeadline = uptime.elapsed() + 3000;
                    return;
                }
                if (uptime.elapsed() < stepDeadline)
                    return;
                endTileBefore = paneLuma(pane.tripTile);
                endCardBefore = paneLuma(pane.turnCard);
                endStatesAtPress = navStates.size();
                const QPoint touchPoint = caps.toTouch(pane.endRoute);
                const oaa::hu::InputChannelHandler::Pointer pointer{touchPoint.x(), touchPoint.y(), 0};
                input->sendTouchIndication(1, &pointer, 0, 0, uptime.elapsed() * 1000);
                input->sendTouchIndication(1, &pointer, 0, 1, uptime.elapsed() * 1000 + 1);
                ++taps;
                out << "receiver_endroute=pressed frame=" << pane.endRoute.x() << "," << pane.endRoute.y()
                    << " touch=" << touchPoint.x() << "," << touchPoint.y() << " states=" << navStates.join(',') << "\n";
                out.flush();
                stepDeadline = uptime.elapsed() + 2 * TAP_DEADLINE_MS;
                ++step;
                break;
            }
            case 1001: {
                endTileChanged = changedFraction(endTileBefore, paneLuma(pane.tripTile), TRACK_LUMA_THRESHOLD);
                endCardChanged = changedFraction(endCardBefore, paneLuma(pane.turnCard), TRACK_LUMA_THRESHOLD);
                const bool inactive = navStates.size() > endStatesAtPress && navStates.last() == "2";
                if ((!inactive || endTileChanged < 0.15 || endCardChanged < 0.5) && uptime.elapsed() < stepDeadline)
                    return;
                out << "receiver_endroute=ended states=" << navStates.join(',') << " tile_changed=" << endTileChanged
                    << " card_changed=" << endCardChanged << "\n";
                out.flush();
                // Watch a little longer: nothing may put the route back on the cluster.
                stepDeadline = uptime.elapsed() + 3000;
                ++step;
                break;
            }
            case 1002: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                endStatesAtWatch = navStates.size();
                endFocusAtWatch = navFocusTypes.size();
                endMediaBefore = paneLuma(pane.media);
                const QPoint touchPoint = caps.toTouch(pane.resumeRow);
                const oaa::hu::InputChannelHandler::Pointer pointer{touchPoint.x(), touchPoint.y(), 0};
                input->sendTouchIndication(1, &pointer, 0, 0, uptime.elapsed() * 1000);
                input->sendTouchIndication(1, &pointer, 0, 1, uptime.elapsed() * 1000 + 1);
                ++taps;
                out << "receiver_endroute=resume frame=" << pane.resumeRow.x() << "," << pane.resumeRow.y() << "\n";
                out.flush();
                stepDeadline = uptime.elapsed() + TAP_DEADLINE_MS;
                ++step;
                break;
            }
            case 1003: {
                endMediaChanged = changedFraction(endMediaBefore, paneLuma(pane.media), TRACK_LUMA_THRESHOLD);
                if (endMediaChanged < 0.02 && uptime.elapsed() < stepDeadline)
                    return;
                out << "receiver_endroute=resumed media_changed=" << endMediaChanged << "\n";
                out.flush();
                // P7.4a: tools/emulator-lab.sh shares the destination again once the app
                // has noted the press; wait for that route to reach the cluster.
                stepDeadline = uptime.elapsed() + 60000;
                ++step;
                break;
            }
            case 1004: {
                const bool again = navStates.mid(endStatesAtWatch).contains("1");
                if (!again && uptime.elapsed() < stepDeadline)
                    return;
                out << "receiver_endroute=again states=" << navStates.join(',') << "\n";
                out.flush();
                // The same three seconds for the panel as before the first press.
                stepDeadline = uptime.elapsed() + 3000;
                ++step;
                break;
            }
            case 1005: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                endStatesAtSecond = navStates.size();
                endFocusAtSecond = navFocusTypes.size();
                const QPoint touchPoint = caps.toTouch(pane.endRoute);
                const oaa::hu::InputChannelHandler::Pointer pointer{touchPoint.x(), touchPoint.y(), 0};
                input->sendTouchIndication(1, &pointer, 0, 0, uptime.elapsed() * 1000);
                input->sendTouchIndication(1, &pointer, 0, 1, uptime.elapsed() * 1000 + 1);
                ++taps;
                out << "receiver_endroute=pressed_again states=" << navStates.join(',') << "\n";
                out.flush();
                stepDeadline = uptime.elapsed() + 2 * TAP_DEADLINE_MS;
                ++step;
                break;
            }
            case 1006: {
                // INACTIVE, and the hand-back just after it on the control channel.
                const bool inactive = navStates.size() > endStatesAtSecond && navStates.last() == "2";
                const bool handedBack = navFocusTypes.mid(endFocusAtSecond).contains(1);
                if ((!inactive || !handedBack) && uptime.elapsed() < stepDeadline)
                    return;
                out << "receiver_endroute=ended_again states=" << navStates.join(',') << "\n";
                out.flush();
                ++step;
                break;
            }
            // P7.8. The flex tile fills with the lab player's notifications; the first row is
            // tapped (the read-aloud stub flashes it); after the phone removes one, the
            // pause is pressed (the rows go, "Notifications paused") and pressed again (the
            // shade is re-read and the rows come back).
            case 1100: {
                if (stepDeadline == 0) {
                    stepDeadline = uptime.elapsed() + 4000;
                    return;
                }
                if (uptime.elapsed() < stepDeadline)
                    return;
                callsBefore = paneLuma(pane.flexTile);
                stepDeadline = uptime.elapsed() + 90000;
                ++step;
                break;
            }
            case 1101:
                if (!callsChanged(pane.flexTile, 0.05, "notifications-arrived"))
                    return;
                stepDeadline = uptime.elapsed() + 2000;
                ++step;
                break;
            case 1102:
            case 1106:
            case 1108: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                callsBefore = paneLuma(pane.flexTile);
                if (step == 2)
                    callsPress(pane.notificationRow, "notification-row");
                else
                    callsPress(pane.pauseIcon, step == 6 ? "pause" : "unpause");
                stepDeadline = uptime.elapsed() + TAP_DEADLINE_MS;
                ++step;
                break;
            }
            case 1103:
                if (!callsChanged(pane.flexTile, 0.005, "row-flash"))
                    return;
                // The app side removes one notification once it has noted the tap.
                stepDeadline = uptime.elapsed() + 8000;
                step = 6;
                break;
            case 1107:
                if (!callsChanged(pane.flexTile, 0.03, "paused"))
                    return;
                stepDeadline = uptime.elapsed() + 3000;
                ++step;
                break;
            case 1109:
                if (!callsChanged(pane.flexTile, 0.03, "unpaused"))
                    return;
                stepDeadline = uptime.elapsed() + 120000;
                ++step;
                break;
            // P7.9. A call rings: wait for Answer to be drawn green, a second more, press it.
            case 1110: {
                if (!answerGreen()) {
                    if (uptime.elapsed() < stepDeadline)
                        return;
                    check(false, "no green Answer appeared in the flex tile");
                    step = 99;
                    break;
                }
                out << "receiver_calls=ringing answer_green=true\n";
                stepDeadline = uptime.elapsed() + 1000;
                ++step;
                break;
            }
            case 1111: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                callsBefore = paneLuma(pane.flexTile);
                callsPress(pane.answer, "answer");
                stepDeadline = uptime.elapsed() + 8000;
                ++step;
                break;
            }
            case 1112: {
                // Answered: the green plate gives way to the in-call row.
                if (answerGreen() && uptime.elapsed() < stepDeadline)
                    return;
                if (!callsChanged(pane.flexTile, 0.10, "answered"))
                    return;
                check(!answerGreen(), "Answer was still green after it was pressed");
                stepDeadline = uptime.elapsed() + 2000;
                step = callsFull ? 13 : 23;
                break;
            }
            // Full controls: Mute lights, Keypad covers the half the map does not take,
            // each key shows in the echo strip, Close takes the keypad away.
            case 1113:
            case 1115:
            case 1117:
            case 1119: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                if (step == 13) {
                    callsBefore = paneLuma(pane.muteBox);
                    callsPress(pane.callRow[0], "mute");
                } else if (step == 15) {
                    callsBefore = paneLuma(pane.media);
                    callsPress(pane.callRow[1], "keypad");
                } else if (step == 17) {
                    callsBefore = paneLuma(pane.keypadEcho);
                    callsPress(pane.keys[CALLS_KEYS[callsKey]], callsKey == 2 ? "key-hash" : "key-digit");
                } else {
                    callsBefore = paneLuma(pane.media);
                    callsPress(pane.keypadClose, "close");
                }
                stepDeadline = uptime.elapsed() + TAP_DEADLINE_MS + 2000;
                ++step;
                break;
            }
            case 1114:
                if (!callsChanged(pane.muteBox, 0.20, "muted"))
                    return;
                stepDeadline = uptime.elapsed() + 1500;
                ++step;
                break;
            case 1116:
                if (!callsChanged(pane.media, 0.30, "keypad-open"))
                    return;
                stepDeadline = uptime.elapsed() + 1000;
                ++step;
                break;
            case 1118:
                // One key in a strip a keypad wide: a thin "1" beside "Muted" is under half a
                // percent of it, and a static strip changes by nothing at all.
                if (!callsChanged(pane.keypadEcho, 0.002, "echo"))
                    return;
                stepDeadline = uptime.elapsed() + 800;
                step = ++callsKey < 3 ? 17 : 19;
                break;
            case 1120:
                if (!callsChanged(pane.media, 0.30, "keypad-closed"))
                    return;
                stepDeadline = uptime.elapsed() + 1500;
                step = 23;
                break;
            // Hang up: from the row of three, or alone across it on the notification route.
            case 1123: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                callsBefore = paneLuma(pane.flexTile);
                callsPress(callsFull ? pane.callRow[2] : pane.hangUpAlone, "hang-up");
                stepDeadline = uptime.elapsed() + 10000;
                ++step;
                break;
            }
            case 1124:
                if (!callsChanged(pane.flexTile, 0.10, "hung-up"))
                    return;
                // A little longer, for the frames of the tile after the call.
                stepDeadline = uptime.elapsed() + 3000;
                ++step;
                break;
            case 1125:
                if (uptime.elapsed() < stepDeadline)
                    return;
                step = 30;
                break;
            // P8.1. The flex tile fills; the first row is tapped (read), tapped again (a
            // second tap stops it) and tapped a third time (read again), each 2.5 s apart.
            // 2.5 s after a tap the 800 ms flash is over, so a row still lit then is being
            // read; how lit is reported, not checked, because only the app knows whether an
            // engine was there to read it. Then 30 s for the last read to finish.
            case 1200: {
                if (stepDeadline == 0) {
                    stepDeadline = uptime.elapsed() + 4000;
                    return;
                }
                if (uptime.elapsed() < stepDeadline)
                    return;
                callsBefore = paneLuma(pane.flexTile);
                stepDeadline = uptime.elapsed() + 90000;
                ++step;
                break;
            }
            case 1201:
                // Two rows, not the calls script's three: 0.053 in the first run.
                if (!callsChanged(pane.flexTile, 0.03, "notifications-arrived"))
                    return;
                stepDeadline = uptime.elapsed() + 2000;
                ++step;
                break;
            case 1202:
            case 1203:
            case 1204: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                if (step == 2) {
                    speechUnlit = paneLuma(pane.flexTile);
                } else {
                    const double lit = changedFraction(speechUnlit, paneLuma(pane.flexTile), TRACK_LUMA_THRESHOLD);
                    out << "receiver_speech=lit " << (step == 3 ? "after-read" : "after-stop") << " " << lit << "\n";
                    callsResults << QString("%1=%2").arg(step == 3 ? "lit-after-read" : "lit-after-stop").arg(lit, 0, 'f', 3);
                }
                callsPress(pane.notificationRow, step == 2 ? "read" : step == 3 ? "stop" : "read-again");
                stepDeadline = uptime.elapsed() + (step == 4 ? 30000 : 2500);
                ++step;
                break;
            }
            case 1205: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                const double lit = changedFraction(speechUnlit, paneLuma(pane.flexTile), TRACK_LUMA_THRESHOLD);
                out << "receiver_speech=lit after-wait " << lit << "\n";
                callsResults << QString("lit-after-wait=%1").arg(lit, 0, 'f', 3);
                step = 30;
                break;
            }
            // P8.2. The guidance drive (700-702) with the voice toggle pressed twice on the
            // way: off 15 s after pulling away, then on again 20 s later, before the drive
            // leaves the route, so the re-route and the arrival are announced with it on.
            // Each press must change the toggle's picture, and the second must put it back
            // as it was. What was spoken is the app's report's to say.
            case 1300: {
                if (!navigation || !trackPlayer || !navStates.contains("1"))
                    return;
                if (stepDeadline == 0) {
                    // Three seconds for the trip panel to be drawn and settle.
                    stepDeadline = uptime.elapsed() + 3000;
                    return;
                }
                if (uptime.elapsed() < stepDeadline)
                    return;
                voiceOnLuma = paneLuma(pane.voiceBox);
                voiceOnFrame = decoder.frames;
                trackPlayer->depart();
                out << "receiver_voice=departed toggle=" << pane.voiceToggle.x() << "," << pane.voiceToggle.y()
                    << " box=" << pane.voiceBox.width << "x" << pane.voiceBox.height << " on_frame=" << voiceOnFrame << "\n";
                out.flush();
                stepDeadline = uptime.elapsed() + 15000;
                ++step;
                break;
            }
            case 1301:
            case 1303: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                const QPoint touchPoint = caps.toTouch(pane.voiceToggle);
                const oaa::hu::InputChannelHandler::Pointer pointer{touchPoint.x(), touchPoint.y(), 0};
                input->sendTouchIndication(1, &pointer, 0, 0, uptime.elapsed() * 1000);
                input->sendTouchIndication(1, &pointer, 0, 1, uptime.elapsed() * 1000 + 1);
                ++taps;
                out << "receiver_voice=pressed " << (step == 1 ? "off" : "on") << " touch=" << touchPoint.x() << ","
                    << touchPoint.y() << " states=" << navStates.join(',') << "\n";
                out.flush();
                stepDeadline = uptime.elapsed() + TAP_DEADLINE_MS;
                ++step;
                break;
            }
            case 1302: {
                voiceOffChanged = changedFraction(voiceOnLuma, paneLuma(pane.voiceBox), TRACK_LUMA_THRESHOLD);
                if (voiceOffChanged < VOICE_TOGGLE_MIN && uptime.elapsed() < stepDeadline)
                    return;
                voiceOffLuma = paneLuma(pane.voiceBox);
                voiceOffFrame = decoder.frames;
                out << "receiver_voice=off changed=" << voiceOffChanged << " frame=" << voiceOffFrame << "\n";
                out.flush();
                stepDeadline = uptime.elapsed() + 20000;
                ++step;
                break;
            }
            case 1304: {
                voiceOnChanged = changedFraction(voiceOffLuma, paneLuma(pane.voiceBox), TRACK_LUMA_THRESHOLD);
                if (voiceOnChanged < VOICE_TOGGLE_MIN && uptime.elapsed() < stepDeadline)
                    return;
                voiceBackToOn = changedFraction(voiceOnLuma, paneLuma(pane.voiceBox), TRACK_LUMA_THRESHOLD);
                out << "receiver_voice=on changed=" << voiceOnChanged << " back_to_on=" << voiceBackToOn
                    << " frame=" << decoder.frames << "\n";
                out.flush();
                ++step;
                break;
            }
            case 1305: {
                if (!trackPlayer->finished())
                    return;
                out << "receiver_guidance=stopped travelled_m=" << qRound(trackPlayer->travelled())
                    << " states=" << navStates.join(',') << "\n";
                out.flush();
                guideArrival.start();
                ++step;
                break;
            }
            case 1306: {
                if (navStates.last() != "2" && guideArrival.elapsed() < GUIDE_ARRIVAL_MS)
                    return;
                ++step;
                break;
            }
            // P9 (plan §8). Parked at the drive's first point until the source has put the
            // route on the cluster, then by --traffic-mode: offer waits for the divided turn
            // card, frames it in the other palette and back (the renders for James), presses
            // Keep, waits for the next offer (the lab has made the queue heavier meanwhile) and
            // presses Take; closure watches the cluster re-route round the closure the lab has
            // moved onto the route, and the closure's colour in the map before it; off drives
            // the drive, and the source must fetch nothing. The app's report, the stand-in
            // server's counts and tools/emulator-lab.sh say the rest.
            case 1400: {
                if (!navigation || !trackPlayer || !labSensors || !navStates.contains("1"))
                    return;
                if (stepDeadline == 0) {
                    // Three seconds for the card, the trip panel and the route line to settle.
                    stepDeadline = uptime.elapsed() + 3000;
                    return;
                }
                if (uptime.elapsed() < stepDeadline)
                    return;
                trafficRouteStates = navStates.size();
                trafficAmberBase = inPane(pane.mapFull, amberPixel);
                trafficClosedBase = inPane(pane.mapFull, closedPixel);
                trafficMapBefore = paneLuma(pane.offerView);
                out << "receiver_traffic=route mode=" << trafficMode << " layout=" << (offerStacked ? "stacked" : "side-by-side")
                    << " picture=" << decoder.frames << " amber=" << trafficAmberBase << " red=" << inPane(pane.mapFull, redPixel)
                    << " closed=" << trafficClosedBase << " eta_s=" << navTimeToArrival << " states=" << navStates.join(',')
                    << " take=" << pane.offerTake.x << "," << pane.offerTake.y << "+" << pane.offerTake.width << "x"
                    << pane.offerTake.height << " keep=" << pane.offerKeep.x << "," << pane.offerKeep.y << "+"
                    << pane.offerKeep.width << "x" << pane.offerKeep.height << "\n";
                out.flush();
                if (trafficMode == "off") {
                    trackPlayer->depart();
                    step = 50;
                } else if (trafficMode == "drive") {
                    // The lab turns traffic on 15 s after the route; the drive leaves once it has
                    // had time to arrive, so the colours are ahead of the vehicle when it goes.
                    stepDeadline = uptime.elapsed() + 30000;
                    step = 60;
                } else {
                    trafficDeadline = uptime.elapsed() + (trafficMode == "closure" ? TRAFFIC_REROUTE_MS : TRAFFIC_OFFER_MS);
                    step = trafficMode == "closure" ? 30 : 1;
                }
                break;
            }
            case 1401:
            case 1406: {
                // Waiting for an offer: the first, or the one after Keep. The amber is sampled
                // once a second on the way, and the view the offer will reframe with it.
                const qint64 now = uptime.elapsed();
                const bool up = offerUp();
                if (now >= trafficSampleAt) {
                    trafficSampleAt = now + 1000;
                    const int amber = inPane(pane.mapFull, amberPixel);
                    if (amber > trafficAmberMax) {
                        trafficAmberMax = amber;
                        trafficAmberMaxPicture = decoder.frames;
                    }
                    if (trafficAmberFirstPicture < 0 && amber - std::max(0, trafficAmberBase) >= TRAFFIC_AMBER_MIN) {
                        trafficAmberFirstPicture = decoder.frames;
                        out << "receiver_traffic=amber picture=" << decoder.frames << " amber=" << amber
                            << " eta_s=" << navTimeToArrival << "\n";
                        out.flush();
                    }
                    if (!up)
                        trafficMapBefore = paneLuma(pane.offerView);
                }
                if (up) {
                    ++trafficOffers;
                    out << "receiver_traffic=offer " << trafficOffers << " up picture=" << decoder.frames
                        << " waited_s=" << (now - (trafficDeadline - TRAFFIC_OFFER_MS)) / 1000 << "\n";
                    out.flush();
                    // Four seconds for the fit's tiles and labels before it is measured.
                    stepDeadline = now + 4000;
                    ++step;
                    break;
                }
                if (now > trafficDeadline) {
                    check(false, QString("no traffic offer %1 appeared within %2 min").arg(trafficOffers + 1).arg(TRAFFIC_OFFER_MS / 60000));
                    step = 99;
                }
                return;
            }
            case 1402:
            case 1407: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                const int index = step == 2 ? 0 : 1;
                trafficFit[index] = changedFraction(trafficMapBefore, paneLuma(pane.offerView), TRACK_LUMA_THRESHOLD);
                out << "receiver_traffic=offer " << (index + 1) << " framed picture=" << decoder.frames
                    << " fit_changed=" << trafficFit[index] << " amber=" << inPane(pane.mapFull, amberPixel)
                    << " red=" << inPane(pane.mapFull, redPixel) << " closed=" << inPane(pane.mapFull, closedPixel)
                    << " night=" << (labSensors->night() ? "true" : "false") << " still_up=" << (offerUp() ? "true" : "false") << "\n";
                out.flush();
                if (index == 1) {
                    // The second offer: Take it.
                    trafficPress(pane.offerTake, "take");
                    stepDeadline = now + 8000;
                    step = 8;
                    break;
                }
                // P9.6's renders: the same card in the other palette, then back.
                check(labSensors->sendNight(!labSensors->night()), "NIGHT_DATA could not be sent for the other palette");
                out << "receiver_traffic=palette night=" << (labSensors->night() ? "true" : "false") << "\n";
                stepDeadline = now + 8000;
                ++step;
                break;
            }
            case 1403: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                trafficOtherPalette = offerUp();
                out << "receiver_traffic=offer 1 other palette picture=" << decoder.frames << " night="
                    << (labSensors->night() ? "true" : "false") << " still_up=" << (trafficOtherPalette ? "true" : "false") << "\n";
                check(labSensors->sendNight(!labSensors->night()), "NIGHT_DATA could not be sent back");
                out << "receiver_traffic=palette night=" << (labSensors->night() ? "true" : "false") << "\n";
                out.flush();
                stepDeadline = now + 8000;
                ++step;
                break;
            }
            case 1404: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                trafficOfferMap = paneLuma(pane.offerView);
                out << "receiver_traffic=offer 1 back picture=" << decoder.frames << " still_up=" << (offerUp() ? "true" : "false") << "\n";
                trafficPress(pane.offerKeep, "keep");
                stepDeadline = now + 8000;
                ++step;
                break;
            }
            case 1405: {
                // Keep: the card whole again at once, and the map back on the vehicle.
                const qint64 now = uptime.elapsed();
                trafficKeepWhole = !offerUp();
                trafficRefollow = changedFraction(trafficOfferMap, paneLuma(pane.offerView), TRACK_LUMA_THRESHOLD);
                if (!(trafficKeepWhole && trafficRefollow >= TRACK_MOVED_MIN) && now < stepDeadline)
                    return;
                out << "receiver_traffic=kept picture=" << decoder.frames << " whole=" << (trafficKeepWhole ? "true" : "false")
                    << " map_changed=" << trafficRefollow << "\n";
                out.flush();
                if (trafficMode == "render") {
                    // The renders need no second offer: a few seconds of the card whole again.
                    stepDeadline = now + 5000;
                    step = 9;
                    break;
                }
                trafficDeadline = now + TRAFFIC_OFFER_MS;
                trafficSampleAt = 0;
                ++step;
                break;
            }
            case 1408: {
                const qint64 now = uptime.elapsed();
                trafficTakeWhole = !offerUp();
                if (!trafficTakeWhole && now < stepDeadline)
                    return;
                out << "receiver_traffic=taken picture=" << decoder.frames << " whole=" << (trafficTakeWhole ? "true" : "false")
                    << " states=" << navStates.join(',') << "\n";
                out.flush();
                // Time for the route through the offer's via point to be computed and followed.
                stepDeadline = now + 30000;
                ++step;
                break;
            }
            case 1409: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                step = 99;
                break;
            }
            case 1430:
            case 1431:
            case 1432: {
                // Closure: sampled at every tick until the new route is followed, since the
                // closure is on the route only between its match and the re-route's answer.
                const qint64 now = uptime.elapsed();
                if (step < 32 || now >= trafficSampleAt) {
                    trafficSampleAt = now + 1000;
                    const int closed = inPane(pane.mapFull, closedPixel);
                    if (step < 32 && closed > trafficClosedMax) {
                        trafficClosedMax = closed;
                        trafficClosedMaxPicture = decoder.frames;
                    }
                    if (step == 32)
                        trafficClosedAfter = closed;
                }
                if (step == 30) {
                    const int at = navStates.indexOf("3", std::max(trafficRouteStates, 0));
                    if (at >= 0) {
                        trafficReroutingAt = at;
                        out << "receiver_traffic=rerouting picture=" << decoder.frames << " states=" << navStates.join(',')
                            << " closed_max=" << trafficClosedMax << "@" << trafficClosedMaxPicture << "\n";
                        out.flush();
                        ++step;
                    } else if (now > trafficDeadline) {
                        check(false, QString("the cluster never heard REROUTING within %1 min").arg(TRAFFIC_REROUTE_MS / 60000));
                        step = 99;
                    }
                    return;
                }
                if (step == 31) {
                    const int at = navStates.indexOf("1", trafficReroutingAt);
                    if (at >= 0) {
                        trafficResumedAt = at;
                        out << "receiver_traffic=rerouted picture=" << decoder.frames << " states=" << navStates.join(',') << "\n";
                        out.flush();
                        // Long enough for the lab to turn traffic off and watch for requests.
                        stepDeadline = now + 60000;
                        ++step;
                    } else if (now > trafficDeadline) {
                        check(false, "no new route became active after the closure's REROUTING");
                        step = 99;
                    }
                    return;
                }
                if (now < stepDeadline)
                    return;
                step = 99;
                break;
            }
            case 1460: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                trackPlayer->depart();
                out << "receiver_traffic=departed picture=" << decoder.frames << " amber=" << inPane(pane.mapFull, amberPixel)
                    << " eta_s=" << navTimeToArrival << "\n";
                out.flush();
                step = 50;
                break;
            }
            case 1450: {
                if (trafficMode == "drive" && uptime.elapsed() >= trafficSampleAt) {
                    trafficSampleAt = uptime.elapsed() + 1000;
                    const int amber = inPane(pane.mapFull, amberPixel);
                    if (amber > trafficAmberMax) {
                        trafficAmberMax = amber;
                        trafficAmberMaxPicture = decoder.frames;
                    }
                }
                if (!trackPlayer->finished())
                    return;
                out << "receiver_traffic=drove travelled_m=" << qRound(trackPlayer->travelled()) << " states=" << navStates.join(',') << "\n";
                out.flush();
                stepDeadline = uptime.elapsed() + 5000;
                ++step;
                break;
            }
            case 1451: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                step = 99;
                break;
            }
            // P10 (plan §8). The weather script: the header's weather item, found drawn (or,
            // off, not) once the first read is in, then by --weather-mode. show, stale and
            // unsupported open the view, frame it in both palettes for the renders and close it
            // with a second press; tap closes it each of the three ways and then presses it
            // during a call; alert waits for a notification, then for the lab's alert over it,
            // and dismisses both alerts; off presses where the item would be; notifier times the
            // temperature against the lab's notifier. The app's report, the stand-in's query log
            // and tools/emulator-lab.sh say the rest.
            case 1500: {
                const qint64 now = uptime.elapsed();
                if (stepDeadline == 0) {
                    stepDeadline = now + 90000;
                    // Off and unsupported have nothing to wait for; stale waits for the lab's read.
                    weatherSettleAt = weatherMode == "stale" ? -1 : now + (weatherMode == "off" || weatherMode == "unsupported" ? 10000 : 3000);
                    return;
                }
                if (weatherMode == "stale" && weatherSettleAt < 0 && weatherHeard("cued") >= 0)
                    weatherSettleAt = now + 4000;
                const int warm = inPane(pane.headerWeather, warmPixel);
                const bool settled = weatherSettleAt >= 0 && now >= weatherSettleAt;
                const bool drawn = weatherMode == "off" || weatherMode == "stale" || weatherMode == "unsupported"
                                       ? settled
                                       : settled && warm >= WEATHER_WARM_HEADER_MIN;
                if (!drawn && now < stepDeadline)
                    return;
                const int clear = weatherHeaderLine("first");
                if (weatherMode == "off") {
                    check(warm <= 2, QString("with weather off the header has %1 warm pixels where the item would be").arg(warm));
                } else {
                    check(drawn, QString("the header's weather item was not drawn within 90 s (%1 warm pixels)").arg(warm));
                    // Plan §5.1: the text never runs into the item.
                    check(clear >= 2, QString("the header text ends %1 px from the weather item").arg(clear));
                }
                weatherHeaderRef = paneLuma(pane.headerWeather);
                weatherTileRef = paneLuma(pane.flexTile);
                weatherTell("shown");
                if (weatherMode == "tap") {
                    step = 20;
                } else if (weatherMode == "alert") {
                    step = 40;
                } else if (weatherMode == "off") {
                    step = 60;
                } else if (weatherMode == "notifier") {
                    step = 70;
                } else {
                    step = 1;
                }
                stepDeadline = now + (weatherMode == "alert" ? 90000 : weatherMode == "notifier" ? 90000 : 2000);
                break;
            }
            // show, stale, unsupported.
            case 1501:
            case 1520:
            case 1524:
            case 1528:
            case 1531: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                weatherTileRef = paneLuma(pane.flexTile);
                weatherPressedAt = now;
                weatherPress(weatherItem, "open");
                stepDeadline = now + TAP_DEADLINE_MS;
                ++step;
                break;
            }
            case 1502:
            case 1521:
            case 1525:
            case 1529:
            case 1532: {
                const bool open = weatherIsOpen();
                if (!weatherWait(open, QString("opened-%1").arg(weatherOpens + 1), weatherTileDetail(weatherTileRef)))
                    return;
                ++weatherOpens;
                weatherTileOpen = paneLuma(pane.flexTile);
                const qint64 now = uptime.elapsed();
                if (step == 29) {
                    // Untouched from here: the app closes it at 30 s.
                    stepDeadline = weatherPressedAt + 45000;
                } else if (step == 32) {
                    weatherTell("call");
                    stepDeadline = now + 120000;
                } else {
                    stepDeadline = now + (step == 2 ? 3000 : 2000);
                }
                ++step;
                break;
            }
            case 1503:
            case 1504: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                weatherRender(weatherMode + "-view");
                weatherHeaderLine(step == 3 ? "view" : "view-other-palette");
                weatherPalette();
                stepDeadline = now + (step == 3 ? 8000 : 5000);
                ++step;
                break;
            }
            case 1505:
            case 1522:
            case 1526: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                weatherTileOpen = paneLuma(pane.flexTile);
                if (step == 26)
                    weatherPress(weatherTileCentre, "view");
                else
                    weatherPress(weatherItem, "close");
                stepDeadline = now + TAP_DEADLINE_MS;
                ++step;
                break;
            }
            case 1506:
            case 1523:
            case 1527: {
                const bool closed = weatherIsClosed();
                const char* what = step == 27 ? "closed-by-view-tap" : "closed-by-second-tap";
                if (!weatherWait(closed, what, weatherTileDetail(weatherTileOpen)))
                    return;
                stepDeadline = uptime.elapsed() + (step == 6 ? 3000 : 2000);
                ++step;
                break;
            }
            case 1507:
                if (uptime.elapsed() < stepDeadline)
                    return;
                step = 99;
                break;
            // tap: the untouched view closes by itself.
            case 1530: {
                const qint64 now = uptime.elapsed();
                const bool closed = weatherIsClosed();
                weatherTimeoutS = (now - weatherPressedAt) / 1000.0;
                if (!weatherWait(closed, "closed-by-timeout", weatherTileDetail(weatherTileOpen) + QString(" after_s=%1").arg(weatherTimeoutS)))
                    return;
                check(closed && weatherTimeoutS >= WEATHER_TIMEOUT_MIN_S && weatherTimeoutS <= WEATHER_TIMEOUT_MAX_S,
                      QString("the untouched view closed %1 s after it was opened").arg(weatherTimeoutS));
                stepDeadline = now + 2000;
                ++step;
                break;
            }
            // tap: the lab's modem call rings over the open view; a press is then ignored.
            case 1533: {
                const bool ringing = answerGreen();
                if (!weatherWait(ringing, "call-ringing", weatherTileDetail(weatherTileOpen)))
                    return;
                const int warm = inPane(pane.flexTile, warmPixel);
                check(!ringing || warm < WEATHER_WARM_TILE_MIN / 4, QString("the weather view stayed over the ringing call: %1 warm").arg(warm));
                stepDeadline = uptime.elapsed() + 2000;
                step = ringing ? 34 : 99;
                break;
            }
            case 1534: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                weatherTileRef = paneLuma(pane.flexTile);
                weatherPress(weatherItem, "in-call");
                stepDeadline = now + 3000;
                ++step;
                break;
            }
            case 1535: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                const int warm = inPane(pane.flexTile, warmPixel);
                const bool ignored = answerGreen() && warm < WEATHER_WARM_TILE_MIN / 4;
                weatherWait(ignored, "in-call-press-ignored", weatherTileDetail(weatherTileRef));
                weatherRender("tap-in-call");
                weatherTell("tapped");
                stepDeadline = now + 90000;
                ++step;
                break;
            }
            case 1536: {
                const bool gone = !answerGreen();
                if (!weatherWait(gone, "call-gone", weatherTileDetail(weatherTileRef)))
                    return;
                stepDeadline = uptime.elapsed() + 6000;
                ++step;
                break;
            }
            case 1537: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                const int warm = inPane(pane.flexTile, warmPixel);
                weatherWait(warm < WEATHER_WARM_TILE_MIN / 4, "view-not-back-after-call", weatherTileDetail(weatherTileOpen));
                step = 99;
                break;
            }
            // alert: the lab player's notification in the tile, then the lab's alert over it.
            case 1540: {
                const bool arrived = changedFraction(weatherTileRef, paneLuma(pane.flexTile), TRACK_LUMA_THRESHOLD) >= WEATHER_NOTIFICATION_MIN;
                if (!weatherWait(arrived, "notification-arrived", weatherTileDetail(weatherTileRef)))
                    return;
                stepDeadline = uptime.elapsed() + 3000;
                ++step;
                break;
            }
            case 1541: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                weatherNotificationTile = paneLuma(pane.flexTile);
                weatherHeaderRef = paneLuma(pane.headerWeather);
                weatherRender("alert-before");
                weatherHeaderLine("before-alert");
                weatherTell("notification");
                stepDeadline = now + 90000;
                ++step;
                break;
            }
            case 1542: {
                const bool shown = changedFraction(weatherNotificationTile, paneLuma(pane.flexTile), TRACK_LUMA_THRESHOLD) >= WEATHER_TILE_CHANGED
                                   && inPane(pane.flexTile, warmPixel) >= WEATHER_WARM_TILE_MIN;
                if (!weatherWait(shown, "alert-took-the-tile", weatherTileDetail(weatherNotificationTile)))
                    return;
                stepDeadline = uptime.elapsed() + 3000;
                step = shown ? 43 : 99;
                break;
            }
            case 1543:
            case 1544: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                if (step == 43) {
                    // Same palette as the reference: the glyph's warning mark.
                    weatherHeaderMark = changedFraction(weatherHeaderRef, paneLuma(pane.headerWeather), TRACK_LUMA_THRESHOLD);
                    weatherWait(weatherHeaderMark >= WEATHER_MARK_MIN, "header-warning-mark",
                                QString("changed=%1 warm=%2").arg(weatherHeaderMark, 0, 'f', 4).arg(inPane(pane.headerWeather, warmPixel)));
                }
                weatherRender("alert");
                weatherHeaderLine(step == 43 ? "alert" : "alert-other-palette");
                weatherPalette();
                stepDeadline = now + (step == 43 ? 8000 : 5000);
                ++step;
                break;
            }
            case 1545:
            case 1547: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                if (step == 47)
                    weatherRender("alert-second");
                weatherTileOpen = paneLuma(pane.flexTile);
                weatherPress(weatherTileCentre, step == 45 ? "dismiss-1" : "dismiss-2");
                stepDeadline = now + 6000;
                ++step;
                break;
            }
            case 1546: {
                const QByteArray tile = paneLuma(pane.flexTile);
                const bool second = changedFraction(weatherTileOpen, tile, TRACK_LUMA_THRESHOLD) >= 0.03
                                    && changedFraction(weatherNotificationTile, tile, TRACK_LUMA_THRESHOLD) >= WEATHER_TILE_CHANGED
                                    && inPane(pane.flexTile, darkRedPixel) >= WEATHER_WARM_TILE_MIN;
                if (!weatherWait(second, "second-alert-shown", weatherTileDetail(weatherTileOpen)))
                    return;
                stepDeadline = uptime.elapsed() + 3000;
                ++step;
                break;
            }
            case 1548: {
                const double fromNotification = changedFraction(weatherNotificationTile, paneLuma(pane.flexTile), TRACK_LUMA_THRESHOLD);
                const bool back = fromNotification >= 0.0 && fromNotification < 0.05
                                  && inPane(pane.flexTile, warmPixel) < WEATHER_WARM_TILE_MIN / 4
                                  && inPane(pane.flexTile, darkRedPixel) < WEATHER_WARM_TILE_MIN / 4;
                if (!weatherWait(back, "notification-back", weatherTileDetail(weatherNotificationTile)))
                    return;
                stepDeadline = uptime.elapsed() + 3000;
                ++step;
                break;
            }
            case 1549: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                // Dismissing takes the alert off the tile, not the mark off the header.
                const double mark = changedFraction(weatherHeaderRef, paneLuma(pane.headerWeather), TRACK_LUMA_THRESHOLD);
                weatherWait(mark >= WEATHER_MARK_MIN, "header-mark-stays", QString("changed=%1").arg(mark, 0, 'f', 4));
                weatherRender("alert-dismissed");
                step = 99;
                break;
            }
            // off: a press where the item would be opens nothing; then the lab's cues.
            case 1560: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                weatherTileRef = paneLuma(pane.flexTile);
                weatherRender("off");
                weatherPress(weatherItem, "where-the-item-would-be");
                stepDeadline = now + 4000;
                ++step;
                break;
            }
            case 1561: {
                const qint64 now = uptime.elapsed();
                if (now < stepDeadline)
                    return;
                const double changed = changedFraction(weatherTileRef, paneLuma(pane.flexTile), TRACK_LUMA_THRESHOLD);
                weatherWait(changed >= 0.0 && changed < 0.02, "off-press-opened-nothing", weatherTileDetail(weatherTileRef));
                weatherTell("off-pressed");
                stepDeadline = now + 90000;
                ++step;
                break;
            }
            case 1562: {
                const bool cued = weatherHeard("off-cued") >= 0;
                if (!weatherWait(cued, "lab-cued-with-weather-off", ""))
                    return;
                stepDeadline = uptime.elapsed() + 5000;
                ++step;
                break;
            }
            case 1563: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                const int warm = inPane(pane.headerWeather, warmPixel);
                weatherWait(warm <= 2, "still-no-item", QString("warm=%1").arg(warm));
                weatherHeaderLine("off-after-cues");
                step = 99;
                break;
            }
            // notifier: the header's temperature, timed from the lab's broadcast.
            case 1570: {
                const qint64 at = weatherHeard("notified");
                if (at < 0 && uptime.elapsed() < stepDeadline)
                    return;
                if (at < 0) {
                    check(false, "the lab never sent the notifier");
                    step = 99;
                    break;
                }
                weatherNotifiedAt = at;
                stepDeadline = uptime.elapsed() + 10000;
                ++step;
                break;
            }
            case 1571: {
                const double changed = changedFraction(weatherHeaderRef, paneLuma(pane.headerWeather), TRACK_LUMA_THRESHOLD);
                const bool moved = changed >= 0.02;
                weatherNotifierMs = QDateTime::currentMSecsSinceEpoch() - weatherNotifiedAt;
                if (!weatherWait(moved, "notifier-temperature",
                                 QString("changed=%1 after_ms=%2").arg(changed, 0, 'f', 3).arg(weatherNotifierMs)))
                    return;
                check(moved && weatherNotifierMs <= WEATHER_NOTIFIER_MAX_MS,
                      QString("the header showed the notifier's read %1 ms after the broadcast").arg(weatherNotifierMs));
                stepDeadline = uptime.elapsed() + 2000;
                ++step;
                break;
            }
            case 1572: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                weatherRender("notifier");
                weatherHeaderLine("after-notifier");
                step = 99;
                break;
            }
            // A head unit that falls behind the stream for a while and then catches up
            // (2026-10-11, report d11eec1e). The picture must decode without an error
            // throughout, keep coming while slow, and flow again once caught up.
            case 1600: {
                stepDeadline = uptime.elapsed() + 5000;
                ++step;
                break;
            }
            case 1601: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                video->setPace(congestionKbps, congestionFrameMs);
                congestionStarts = videoStarts;
                congestionFrames = decoder.frames;
                congestionErrors = decoder.errors;
                out << "receiver_congestion=slow kbps=" << congestionKbps << " frame_ms=" << congestionFrameMs
                    << " for_ms=" << congestionSlowMs << " decoded=" << decoder.frames << "\n";
                out.flush();
                stepDeadline = uptime.elapsed() + congestionSlowMs;
                ++step;
                break;
            }
            case 1602: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                video->setPace(0, 0);
                const int restarts = videoStarts - congestionStarts;
                const int slowFrames = decoder.frames - congestionFrames;
                out << "receiver_congestion=caught-up decoded_while_slow=" << slowFrames
                    << " restarts_while_slow=" << restarts << " paced=" << video->paced()
                    << " max_ack_lag_ms=" << video->maxLagMs() << "\n";
                out.flush();
                check(slowFrames > 0, "no picture decoded while the head unit was slow");
                if (congestionExpect == "restart")
                    check(restarts >= 1, "the source never restarted the stream while the head unit was slow");
                else if (congestionExpect == "none")
                    check(restarts == 0, QString("the source restarted the stream %1 times").arg(restarts));
                congestionStarts = videoStarts;
                congestionFrames = decoder.frames;
                stepDeadline = uptime.elapsed() + congestionNormalMs;
                ++step;
                break;
            }
            case 1603: {
                if (uptime.elapsed() < stepDeadline)
                    return;
                const int frames = decoder.frames - congestionFrames;
                const double fps = frames * 1000.0 / std::max(1, congestionNormalMs);
                out << "receiver_congestion=done decoded_after=" << frames << " fps_after="
                    << QString::number(fps, 'f', 1) << " restarts_after=" << (videoStarts - congestionStarts)
                    << " decode_errors=" << decoder.errors << " (" << (decoder.errors - congestionErrors)
                    << " since the slow phase began)\n";
                out.flush();
                check(decoder.errors == 0, "decode errors through the congestion");
                check(fps >= 5.0, QString("the stream did not flow again once the head unit caught up: %1 fps")
                                      .arg(fps, 0, 'f', 1));
                step = 99;
                break;
            }
            default:
                scriptTimer.stop();
                QTimer::singleShot(holdMs, &app, [&]() {
                    if (session)
                        session->stop(1);
                });
                break;
            }
        });
        scriptTimer.start(100);
    }

    QTimer::singleShot(timeoutMs, &app, [&]() {
        out << "receiver_timeout=" << timeoutMs << "\n";
        out.flush();
        app.exit(3);
    });
    status = app.exec();
    scriptTimer.stop();
    if (projection) {
        check(decoder.frames > 0, "no decoded pictures");
        check(decoder.errors == 0, QString("%1 decode errors").arg(decoder.errors));
        if (script == "taps")
            check(taps == 4 && step >= 9, QString("the tap script stopped at step %1").arg(step));
        if (script == "focus")
            check(step >= 7, QString("the focus script stopped at step %1").arg(step));
        if (script == "transport")
            check(step >= 4, QString("the transport script stopped at step %1").arg(step));
        if (script == "audio")
            check(step >= 1, QString("the audio script stopped at step %1").arg(step));
        if (script == "track")
            check(step >= 3, QString("the track script stopped at step %1").arg(step));
        if (script == "navigation")
            check(step >= 2, QString("the navigation script stopped at step %1").arg(step));
        if (script == "daynight")
            check(step >= 4, QString("the daynight script stopped at step %1").arg(step));
        if (script == "controls")
            check(controlIndex == controlPresses.size(),
                  QString("the controls script stopped after %1 presses").arg(controlIndex));
        if (script == "calls") {
            // P7.8, P7.9, from the receiver's side: every press changed what it should. The
            // app's report says what each press did to the call.
            check(step == 30, QString("the calls script stopped at step %1").arg(step));
            out << "receiver_calls=controls " << (callsFull ? "full" : "basic") << " " << callsResults.join(' ') << "\n";
        }
        if (script == "speech") {
            // P8.1, from the receiver's side: three taps landed. What each did is the app's.
            check(step == 30, QString("the speech script stopped at step %1").arg(step));
            out << "receiver_speech=taps " << callsResults.join(' ') << "\n";
        }
        if (script == "endroute" && navigation) {
            // P7.4, from the receiver's side: the press ended the route on the cluster and
            // on the picture. The app's report says it was End route that did it.
            check(step >= 7, QString("the endroute script stopped at step %1").arg(step));
            check(endStatesAtPress > 0 && navStates.mid(0, endStatesAtPress).contains("1"),
                  "End route was pressed before the cluster had a route");
            // Up to the end of the watch: the second route comes later, on purpose.
            const QStringList after = endStatesAtPress < 0 || endStatesAtWatch < endStatesAtPress
                                          ? QStringList()
                                          : navStates.mid(endStatesAtPress, endStatesAtWatch - endStatesAtPress);
            check(!after.isEmpty() && after.first() == "2",
                  QString("the cluster was not told INACTIVE after End route: states %1").arg(navStates.join(',')));
            check(!after.contains("1") && !after.contains("3"),
                  QString("a route came back on the cluster after End route: states %1").arg(navStates.join(',')));
            check(endTileChanged >= 0.15,
                  QString("the trip panel's tile changed only %1 after End route").arg(endTileChanged));
            check(endCardChanged >= 0.5,
                  QString("the turn card's area changed only %1 after End route").arg(endCardChanged));
            // P7.4a: the cluster handed back when the route ended, not at the end of the
            // session; asked for again by the next route before that route reached the
            // cluster; and handed back again at the second End route. Each request is
            // placed by how many cluster states had arrived when it came.
            QStringList focus;
            for (int i = 0; i < navFocusTypes.size(); ++i)
                focus << QString("%1@%2").arg(navFocusTypes[i]).arg(navFocusAtState[i]);
            auto firstRequest = [&](int type, int from, int minState, int maxState) {
                for (int i = std::max(from, 0); i < navFocusTypes.size(); ++i)
                    if (navFocusTypes[i] == type && navFocusAtState[i] >= minState && navFocusAtState[i] <= maxState)
                        return i;
                return -1;
            };
            const int secondActive = endStatesAtWatch < 0 ? -1 : navStates.indexOf("1", endStatesAtWatch);
            const int handBack = firstRequest(1, 0, endStatesAtPress, std::max(endStatesAtWatch, endStatesAtPress));
            check(handBack >= 0 && handBack < std::max(endFocusAtWatch, 0),
                  QString("the cluster was not handed back when End route was pressed: requests %1").arg(focus.join(',')));
            check(firstRequest(2, handBack + 1, endStatesAtWatch, secondActive) > handBack && handBack >= 0
                      && secondActive >= 0,
                  QString("the next route did not ask for the cluster before using it: requests %1 states %2")
                      .arg(focus.join(','), navStates.join(',')));
            check(firstRequest(1, std::max(endFocusAtSecond, 0), std::max(endStatesAtSecond, 0), navStates.size()) >= 0
                      && endStatesAtSecond >= 0,
                  QString("the cluster was not handed back at the second End route: requests %1").arg(focus.join(',')));
            out << "receiver_endroute=focus requests=" << focus.join(',') << "\n";
            out << "receiver_endroute=states=" << navStates.join(',') << " tile_changed=" << endTileChanged
                << " card_changed=" << endCardChanged << " media_changed=" << endMediaChanged << "\n";
        }
        if (script == "voice") {
            // P8.2, from the receiver's side: both presses landed and each changed the
            // toggle, the second back to the picture it started from. The drive itself is
            // checked below as the guidance script's is.
            check(step >= 7, QString("the voice script stopped at step %1").arg(step));
            check(voiceOffChanged >= VOICE_TOGGLE_MIN,
                  QString("the first press changed the toggle only %1").arg(voiceOffChanged));
            check(voiceOnChanged >= VOICE_TOGGLE_MIN,
                  QString("the second press changed the toggle only %1").arg(voiceOnChanged));
            check(voiceBackToOn >= 0.0 && voiceBackToOn <= VOICE_TOGGLE_SAME_MAX,
                  QString("back on, the toggle differs by %1 from how it started").arg(voiceBackToOn));
            out << "receiver_voice=toggle off_changed=" << voiceOffChanged << " on_changed=" << voiceOnChanged
                << " back_to_on=" << voiceBackToOn << " on_frame=" << voiceOnFrame << " off_frame=" << voiceOffFrame << "\n";
        }
        if (script == "traffic") {
            // P9, from the receiver's side: what the picture and the cluster showed. The app's
            // report and the stand-in's counts are checked by tools/emulator-lab.sh.
            check(step >= 99, QString("the traffic script stopped at step %1").arg(step));
            if (trafficMode == "offer" || trafficMode == "render") {
                check(trafficAmberMax - std::max(0, trafficAmberBase) >= TRAFFIC_AMBER_MIN,
                      QString("the route line never turned amber in the map pane: %1 pixels at most, %2 before")
                          .arg(trafficAmberMax).arg(trafficAmberBase));
                const int wanted = trafficMode == "offer" ? 2 : 1;
                check(trafficOffers >= wanted, QString("%1 offers appeared on the turn card, not %2").arg(trafficOffers).arg(wanted));
                check(trafficFit[0] >= TRACK_MOVED_MIN, QString("the map did not reframe for the first offer: %1").arg(trafficFit[0]));
                check(trafficOtherPalette, "the offer was not on the card in the other palette");
                check(trafficKeepWhole, "the card was not whole again after Keep");
                check(trafficRefollow >= TRACK_MOVED_MIN, QString("the map did not go back to the vehicle after Keep: %1").arg(trafficRefollow));
                check(trafficMode == "render" || trafficTakeWhole, "the card was not whole again after Take");
                check(etaFirstRiseFrom >= 0, "the cluster's time to the destination never rose by traffic's delay");
            } else if (trafficMode == "closure") {
                check(trafficReroutingAt > 0 && trafficResumedAt > trafficReroutingAt,
                      QString("the closure did not re-route on the cluster: states %1").arg(navStates.join(',')));
                check(trafficClosedMax - std::max(0, trafficClosedBase) >= TRAFFIC_CLOSED_MIN,
                      QString("the closure's colour never showed in the map pane: %1 pixels at most, %2 before")
                          .arg(trafficClosedMax).arg(trafficClosedBase));
            } else {
                check(trackPlayer && trackPlayer->finished(), "the drive did not finish");
                if (trafficMode == "drive")
                    check(trafficAmberMax - std::max(0, trafficAmberBase) >= TRAFFIC_AMBER_MIN,
                          QString("the route line never turned amber on the drive: %1 pixels at most, %2 before")
                              .arg(trafficAmberMax).arg(trafficAmberBase));
            }
            out << "receiver_traffic=summary mode=" << trafficMode << " offers=" << trafficOffers << " amber=" << trafficAmberBase
                << "->" << trafficAmberMax << "@" << trafficAmberMaxPicture << " first_amber_picture=" << trafficAmberFirstPicture
                << " fit=" << trafficFit[0] << "," << trafficFit[1] << " refollow=" << trafficRefollow
                << " keep_whole=" << (trafficKeepWhole ? "true" : "false") << " take_whole=" << (trafficTakeWhole ? "true" : "false")
                << " closed=" << trafficClosedBase << "->" << trafficClosedMax << "@" << trafficClosedMaxPicture << "->" << trafficClosedAfter
                << " eta_first_rise=" << etaFirstRiseFrom << "->" << etaFirstRiseTo << " eta_max_rise=" << etaMaxRise
                << " states=" << navStates.join(',') << "\n";
        }
        if (script == "weather") {
            // P10, from the receiver's side: what the header and the flex tile showed. The
            // app's report and the stand-in's query log are checked by tools/emulator-lab.sh.
            check(step >= 99, QString("the weather script stopped at step %1").arg(step));
            out << "receiver_weather=summary mode=" << weatherMode << " opens=" << weatherOpens
                << " timeout_s=" << weatherTimeoutS << " header_mark=" << weatherHeaderMark
                << " notifier_ms=" << weatherNotifierMs << " " << weatherResults.join(' ') << "\n";
        }
        if (guidanceDrive && navigation) {
            // P6.5, from the receiver's own decode: the source put a route on the cluster,
            // counted it down as the vehicle drove, said REROUTING when the drive left it,
            // came back with a new route, and said the drive arrived. Nothing here knows
            // what the right maneuvers were; that the icons match the road is a question
            // for a person at a real cluster.
            check(step >= (script == "voice" ? 7 : 3), QString("the guidance drive stopped at step %1").arg(step));
            const int active = navStates.indexOf("1");
            const int rerouting = navStates.indexOf("3", std::max(active, 0));
            const int resumed = rerouting < 0 ? -1 : navStates.indexOf("1", rerouting);
            const int arrived = navStates.lastIndexOf("2");
            check(active >= 0, "the cluster was never told a route was active");
            check(rerouting > active, "the drive left the route and the cluster never heard REROUTING");
            check(resumed > rerouting, "no new route became active after re-routing");
            check(arrived > resumed && arrived > navStates.lastIndexOf("1"),
                  QString("the drive ended without the cluster going inactive: states %1")
                      .arg(navStates.join(',')));
            check(guideManeuvers.size() >= 3,
                  QString("only %1 distinct maneuvers reached the cluster").arg(guideManeuvers.size()));
            check(!guideManeuvers.isEmpty() && guideManeuvers.last() == 39,
                  "the last maneuver before arriving was not DESTINATION (39)");
            // Rounding to tenths of a mile can hold a value, never raise it by much.
            check(guideFalls >= 20, QString("the countdown fell only %1 times").arg(guideFalls));
            check(guideWorstRise < 50.0,
                  QString("a countdown rose by %1 m while its maneuver held").arg(guideWorstRise));
            check(navigation->smallestGapMs >= caps.navigationIntervalMs * 9 / 10,
                  QString("updates arrived %1 ms apart against an advertised floor of %2")
                      .arg(navigation->smallestGapMs)
                      .arg(caps.navigationIntervalMs));
            // The route is driven over a map with street names on it, so some maneuver
            // must say which road it turns onto, and some update which road is under the
            // vehicle; unnamed ramps and the destination say neither.
            check(guideNamedSteps > 0, "no maneuver named the road it turns onto");
            check(guideCurrentRoads > 0, "no update named the road being driven");
            QStringList maneuvers;
            for (int m : guideManeuvers)
                maneuvers << QString::number(m);
            out << "receiver_guidance=states=" << navStates.join(',')
                << " maneuvers=" << maneuvers.join(',') << " countdown_falls=" << guideFalls
                << " countdown_rises=" << guideRises << " worst_rise_m=" << qRound(guideWorstRise)
                << " smallest_gap_ms=" << navigation->smallestGapMs
                << " named_steps=" << guideNamedSteps << "/" << guideSteps
                << " current_roads=" << guideCurrentRoads
                << " travelled_m=" << (trackPlayer ? qRound(trackPlayer->travelled()) : 0) << "\n";
        }
        if (labSensors) {
            // P5.2: the receiver's own record of what it was asked for and what it sent,
            // to be compared against the source's sensor_populated/sensor_silent line.
            auto joined = [](const QList<int>& values) {
                QStringList text;
                for (int value : values)
                    text << QString::number(value);
                return text.isEmpty() ? QString("-") : text.join(",");
            };
            check(labSensors->requested.size() == std::size(LAB_SENSOR_TYPES),
                  QString("the source subscribed to %1 of %2 advertised sensors")
                      .arg(labSensors->requested.size())
                      .arg(std::size(LAB_SENSOR_TYPES)));
            out << "receiver_sensors_requested=" << joined(labSensors->requested)
                << " receiver_sensors_populated=" << joined(labSensors->populated) << "\n";
            if (trackPlayer) {
                // What was actually driven, and in which units. The scales are printed
                // because the source infers them rather than being told: a report that
                // says only "the map followed" cannot be checked against a source that
                // read the bearing at the wrong scale and followed something else.
                check(labSensors->fixes > 0, "the track played no positions");
                out << "receiver_track=fixes=" << labSensors->fixes
                    << " speed_events=" << labSensors->slowEvents
                    << " interval_ms=" << caps.trackIntervalMs
                    << " travelled_m=" << qRound(trackPlayer->travelled())
                    << " corners=" << trackPlayer->corners()
                    << " speed_max_ms=" << trackPlayer->maxSpeed()
                    << " scales=position:1e7,bearing:1e3,speed:1e3\n";
            }
        }
        if (caps.mediaInfo) {
            out << "receiver_media_status=" << statusMessages
                << " receiver_media_metadata=" << metadataMessages
                << " receiver_media_state=" << playbackState
                << " receiver_media_album_art=" << albumArtBytes
                << " receiver_media_source=" << (sourceApp.isEmpty() ? QString("-") : sourceApp)
                << "\n";
        }
        if (navigation) {
            // What the second reader made of it. The maneuver is a number, the distance
            // is the source's own text, and neither a road name nor a destination is
            // printed: this evidence is about whether the cluster was driven, not about
            // where anybody was going (P6 plan §9).
            check(navigation->unexpected == 0,
                  QString("%1 unexpected messages on the cluster channel")
                      .arg(navigation->unexpected));
            if (caps.navigationExpectSequence) {
                check(navigation->states > 0, "the cluster was never told a navigation state");
                check(navigation->notifications > 0, "no maneuver arrived on the cluster channel");
                check(navigation->distances > 0, "no distance arrived on the cluster channel");
                // P6 plan §2: semantic, not pictorial, and no lanes this project could
                // fill from a real route.
                check(navLanes == 0, QString("%1 lane groups were sent").arg(navLanes));
                // The clock format is unrecorded and the source does not know the
                // vehicle's.
                check(!navEtaString, "an estimated time of arrival string was sent");
                check(navTimeToStep >= 0, "no time to the next maneuver was sent");
            } else {
                // A head unit that never announced a cluster must be told nothing at all:
                // the P6 plan §3 sequence waits for the cluster rather than assuming it.
                check(navigation->states == 0,
                      QString("%1 navigation states were sent to a cluster that never "
                              "announced itself")
                          .arg(navigation->states));
                check(navigation->notifications == 0 && navigation->distances == 0,
                      QString("%1 maneuvers and %2 distances were sent to a cluster that "
                              "never announced itself")
                          .arg(navigation->notifications)
                          .arg(navigation->distances));
            }
            out << "receiver_navigation=states=" << navStates.join(',')
                << " notifications=" << navigation->notifications
                << " distances=" << navigation->distances
                << " updates=" << navigation->updates
                << " cluster=" << (navigation->announceCluster() ? "announced" : "none")
                << " maneuver=" << navManeuver << " lanes=" << navLanes
                << " destinations=" << navDestinations
                << " expect=" << (caps.navigationExpectSequence ? "sequence" : "silence")
                << " unit=" << navStepUnit << " value=" << navStepValue
                << " to_step_s=" << navTimeToStep << " to_arrival_s=" << navTimeToArrival
                << " eta_string=" << (navEtaString ? "true" : "false")
                << " unexpected=" << navigation->unexpected << "\n";
        }
        if (audio) {
            // The window Prodigy advertises for audio is its own MAX_UNACKED, not Ford's;
            // what matters here is that every buffer was acknowledged and that the
            // underflow injection, when asked for, went out unanswered.
            check(audio->buffers > 0, "no audio buffers arrived");
            check(audio->largest <= AUDIO_FRAGMENT_LIMIT,
                  QString("an audio buffer of %1 bytes would fragment").arg(audio->largest));
            out << "receiver_audio_buffers=" << audio->buffers
                << " receiver_audio_bytes=" << audio->bytes
                << " receiver_audio_largest=" << audio->largest
                << " receiver_audio_session=" << audioSession
                << " receiver_audio_config=" << audioConfigIndex
                << " receiver_audio_stopped=" << (audioStopped ? "true" : "false")
                << " receiver_audio_underflows_sent=" << audio->underflowsSent << "\n";
        }
        out << "receiver_video_decoded_frames=" << decoder.frames
            << " receiver_video_configs=" << decoder.configs
            << " receiver_video_decode_errors=" << decoder.errors
            << " receiver_video_size=" << decoder.width << "x" << decoder.height
            << " receiver_taps_sent=" << taps << " receiver_keys_sent=" << keys << "\n";
        out << "receiver_checks=" << checks << " receiver_check_failures=" << failures << "\n";
        out.flush();
    }
    logger.close();
    if (videoOut.isOpen())
        videoOut.close();
    if (audioOut.isOpen())
        audioOut.close();
    if (etaOut.isOpen())
        etaOut.close();
    if (session)
        session->finalize();
    return failures > 0 ? 4 : status;
}
