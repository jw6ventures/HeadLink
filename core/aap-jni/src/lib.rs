//! Android native bridge. `JNI_OnLoad` registers the `NativeBridge` methods with
//! explicit signatures, so a Kotlin/native mismatch fails at library load rather than
//! at call time. Handles are session generations, never pointers (see `manager`), and
//! no panic or pending Java exception crosses the JNI boundary.
use aap_session::{
    media::BufferFlags,
    navigation::{Guidance, RouteState},
    now_playing::{NowPlaying, PlaybackState},
    Category, Config, Session, Stage,
};
use jni_sys::{
    jboolean, jbyteArray, jclass, jint, jlong, jobject, jstring, jvalue, JNIEnv, JNINativeMethod,
    JavaVM, JNI_ERR, JNI_OK, JNI_VERSION_1_6,
};
use manager::{
    EventKind, Events, Manager, CONFIG_LIMIT, GUIDANCE_ARRIVED, GUIDANCE_ENDED, GUIDANCE_GUIDING,
    GUIDANCE_IDLE, GUIDANCE_REROUTING, NOW_PLAYING_INTERNAL_ERROR, NOW_PLAYING_INVALID,
    START_CALLBACK_INVALID, START_INTERNAL_ERROR, START_INVALID_CONFIG, START_INVALID_DESCRIPTOR,
    SUBMIT_INTERNAL_ERROR, SUBMIT_INVALID_BUFFER,
};
use std::{
    ffi::{c_void, CStr, CString},
    panic::{catch_unwind, AssertUnwindSafe},
    path::Path,
    ptr::null_mut,
    sync::OnceLock,
    time::Instant,
};

pub mod manager;

/// In-memory version-only exchange against the spec §7.2 fixtures. It proves that
/// the packaged engine runs; it exercises no USB descriptor, TLS, or head unit (the TLS
/// self-test runs with the identity, see `manager::ensure_identity`).
pub fn probe() -> String {
    let request = fixture(include_str!(
        "../../../protocol/fixtures/version-request.hex"
    ));
    let expected = fixture(include_str!(
        "../../../protocol/fixtures/version-response.hex"
    ));
    let now = Instant::now();
    let mut session = Session::new(Config::default(), 0, now);
    session.receive(&request, now);
    let response = session.pending_output().to_vec();
    session.output_written(response.len(), now);
    // Synthetic plaintext SSL_HANDSHAKE message carrying one placeholder byte.
    session.receive(&[0, 3, 0, 3, 0, 3, 0x16], now);
    let report = session.report();
    let pass = response == expected
        && report.last_completed == Some(Stage::Version)
        && report.outcome.as_ref().map(|o| o.category) == Some(Category::StageLimit);
    format!(
        "native_library=aap_jni {}\nabi={}-{}\ntls_library={}\nself_test={}\n\
         self_test_scope=in-memory version-only exchange with spec fixtures; \
         no USB, TLS, or head unit\n{report}",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::ARCH,
        std::env::consts::OS,
        aap_tls::library_version(),
        if pass { "pass" } else { "FAIL" },
    )
}

fn fixture(text: &str) -> Vec<u8> {
    text.split_whitespace()
        .filter_map(|b| u8::from_str_radix(b, 16).ok())
        .collect()
}

/// Class whose natives are registered, and the callback method invoked on it.
pub const BRIDGE_CLASS: &CStr = c"com/jw6ventures/headlink/NativeBridge";
pub const CALLBACK_METHOD: &CStr = c"onNativeEvent";
pub const CALLBACK_SIGNATURE: &CStr = c"(JI[B)V";

fn manager() -> &'static Manager {
    static MANAGER: OnceLock<Manager> = OnceLock::new();
    MANAGER.get_or_init(Manager::default)
}

/// Calls a JNI function-table entry. Every entry used here is populated by conforming
/// VMs; a missing one is treated as a failed call by the callers' null/error checks.
macro_rules! jni {
    ($env:expr, $name:ident $(, $arg:expr)*) => {
        match (**$env).$name {
            Some(f) => Some(f($env $(, $arg)*)),
            None => None,
        }
    };
}

/// Clears any pending exception, logging it through the VM. Returns whether one was pending.
unsafe fn clear_exception(env: *mut JNIEnv) -> bool {
    let pending = jni!(env, ExceptionCheck).unwrap_or(0) != 0;
    if pending {
        jni!(env, ExceptionDescribe);
        jni!(env, ExceptionClear);
    }
    pending
}

/// # Safety
/// Called by the VM when the library loads, with a valid `JavaVM`.
#[no_mangle]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    catch_unwind(AssertUnwindSafe(|| unsafe { register(vm) })).unwrap_or(JNI_ERR)
}

unsafe fn register(vm: *mut JavaVM) -> jint {
    let mut env: *mut c_void = null_mut();
    let Some(get_env) = (**vm).GetEnv else {
        return JNI_ERR;
    };
    if get_env(vm, &mut env, JNI_VERSION_1_6) != JNI_OK {
        return JNI_ERR;
    }
    let env = env as *mut JNIEnv;
    let class = jni!(env, FindClass, BRIDGE_CLASS.as_ptr()).unwrap_or(null_mut());
    if class.is_null() {
        clear_exception(env);
        return JNI_ERR;
    }
    let methods: [(&CStr, &CStr, *mut c_void); 12] = [
        (
            c"nativeProbe",
            c"()Ljava/lang/String;",
            native_probe as *mut c_void,
        ),
        (
            c"nativeStart",
            c"(I[BLcom/jw6ventures/headlink/SessionCallback;)J",
            native_start as *mut c_void,
        ),
        (c"nativeStop", c"(J)V", native_stop as *mut c_void),
        (
            c"nativeGetReport",
            c"(J)[B",
            native_get_report as *mut c_void,
        ),
        (
            c"nativeEnsureIdentity",
            c"(Ljava/lang/String;Z)Ljava/lang/String;",
            native_ensure_identity as *mut c_void,
        ),
        (
            c"nativeSubmitVideo",
            c"(JIJLjava/nio/ByteBuffer;II)I",
            native_submit_video as *mut c_void,
        ),
        (
            c"nativeVideoFailed",
            c"(JLjava/lang/String;)V",
            native_video_failed as *mut c_void,
        ),
        (
            c"nativeRequestShutdown",
            c"(J)V",
            native_request_shutdown as *mut c_void,
        ),
        (
            c"nativeSubmitNowPlaying",
            c"(JIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
            native_submit_now_playing as *mut c_void,
        ),
        (
            c"nativeSubmitGuidance",
            c"(JIJIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;DIDI)I",
            native_submit_guidance as *mut c_void,
        ),
        (
            c"nativeSubmitAudio",
            c"(JJLjava/nio/ByteBuffer;II)I",
            native_submit_audio as *mut c_void,
        ),
        (
            c"nativeAudioFailed",
            c"(JLjava/lang/String;)V",
            native_audio_failed as *mut c_void,
        ),
    ];
    let table: Vec<JNINativeMethod> = methods
        .iter()
        .map(|(name, signature, function)| JNINativeMethod {
            name: name.as_ptr() as *mut _,
            signature: signature.as_ptr() as *mut _,
            fnPtr: *function,
        })
        .collect();
    let registered = jni!(
        env,
        RegisterNatives,
        class,
        table.as_ptr(),
        table.len() as jint
    );
    jni!(env, DeleteLocalRef, class);
    if registered != Some(JNI_OK) {
        clear_exception(env);
        return JNI_ERR;
    }
    JNI_VERSION_1_6
}

/// `NativeBridge.nativeProbe(): String?`. Returns null only if the VM cannot allocate.
unsafe extern "system" fn native_probe(env: *mut JNIEnv, _class: jclass) -> jstring {
    let text = catch_unwind(probe).unwrap_or_else(|_| "native_probe=panic\n".to_owned());
    new_ascii_string(env, text)
}

/// New Java string from report text, which is ASCII and so also modified UTF-8. Any other
/// byte is replaced first. Null only if the VM cannot allocate.
unsafe fn new_ascii_string(env: *mut JNIEnv, text: String) -> jstring {
    let text: String = text
        .chars()
        .map(|c| if c.is_ascii() && c != '\0' { c } else { '?' })
        .collect();
    let text = CString::new(text).unwrap_or_else(|_| c"native_text=invalid".to_owned());
    let string = jni!(env, NewStringUTF, text.as_ptr()).unwrap_or(null_mut());
    clear_exception(env);
    string
}

/// `NativeBridge.nativeEnsureIdentity(dir, replace): String?`. Loads or creates the source
/// identity in the app-private `dir` (replacing it when asked) and runs the TLS
/// self-test. Blocking: RSA key generation can take a noticeable time; never call on
/// the UI thread or after a head unit is waiting.
unsafe extern "system" fn native_ensure_identity(
    env: *mut JNIEnv,
    _class: jclass,
    dir: jstring,
    replace: jboolean,
) -> jstring {
    let Some(path) = read_string(env, dir) else {
        return new_ascii_string(env, "identity_error=unreadable directory\n".into());
    };
    let text = catch_unwind(|| manager::ensure_identity(Path::new(&path), replace != 0))
        .unwrap_or_else(|_| "identity_error=panic\n".to_owned());
    new_ascii_string(env, text)
}

/// `NativeBridge.nativeStart(ownedFd, config, callback): Long`. Ownership of `fd`
/// transfers on invocation; it is closed on every failure path here and by the session
/// after a successful start.
unsafe extern "system" fn native_start(
    env: *mut JNIEnv,
    _class: jclass,
    fd: jint,
    config: jbyteArray,
    callback: jobject,
) -> jlong {
    let Some(fd) = manager::take_descriptor(fd) else {
        return START_INVALID_DESCRIPTOR;
    };
    let result = catch_unwind(AssertUnwindSafe(|| unsafe {
        let config = match read_config(env, config) {
            Some(config) => config,
            None => return START_INVALID_CONFIG,
        };
        match JniEvents::new(env, callback) {
            Some(events) => manager().start(fd, &config, Box::new(events)),
            None => START_CALLBACK_INVALID,
        }
    }));
    // A panic has already dropped (closed) `fd` while unwinding.
    result.unwrap_or(START_INTERNAL_ERROR)
}

unsafe fn read_config(env: *mut JNIEnv, config: jbyteArray) -> Option<Vec<u8>> {
    if config.is_null() {
        return None;
    }
    let len = jni!(env, GetArrayLength, config)?;
    if len < 0 || len as usize > CONFIG_LIMIT {
        return None;
    }
    let mut bytes = vec![0u8; len as usize];
    jni!(
        env,
        GetByteArrayRegion,
        config,
        0,
        len,
        bytes.as_mut_ptr().cast()
    )?;
    (!clear_exception(env)).then_some(bytes)
}

/// `NativeBridge.nativeStop(handle)`. Blocks until the session worker has finished.
unsafe extern "system" fn native_stop(_env: *mut JNIEnv, _class: jclass, handle: jlong) {
    let _ = catch_unwind(|| manager().stop(handle));
}

/// `NativeBridge.nativeSubmitVideo(handle, flags, presentationUs, buffer, offset, size)`.
/// Called on the encoder thread with a direct `ByteBuffer` from `MediaCodec`: the bytes are
/// copied before this returns, so the caller may release the output buffer immediately.
/// Returns a `Submit` value (0 accepted, 1..=5 dropped or refused) or a negative
/// `SUBMIT_*` code. Never blocks on the transport.
unsafe extern "system" fn native_submit_video(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    flags: jint,
    presentation_us: jlong,
    buffer: jobject,
    offset: jint,
    size: jint,
) -> jint {
    let Some(bytes) = direct_buffer(env, buffer, offset, size) else {
        return SUBMIT_INVALID_BUFFER;
    };
    let result = catch_unwind(AssertUnwindSafe(|| {
        manager().submit_video(
            handle,
            BufferFlags::from_android(flags),
            presentation_us,
            bytes,
        )
    }));
    result.unwrap_or(SUBMIT_INTERNAL_ERROR)
}

/// `NativeBridge.nativeSubmitAudio(handle, presentationUs, buffer, offset, size)` (P5.3).
/// Called on the app's audio thread with a direct `ByteBuffer` of signed 16-bit
/// little-endian PCM at the format the `AudioStart` event named; the bytes are copied
/// before this returns. Returns a `Submit` value or a negative `SUBMIT_*` code. Never
/// blocks on the transport.
unsafe extern "system" fn native_submit_audio(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    presentation_us: jlong,
    buffer: jobject,
    offset: jint,
    size: jint,
) -> jint {
    let Some(bytes) = direct_buffer(env, buffer, offset, size) else {
        return SUBMIT_INVALID_BUFFER;
    };
    let result = catch_unwind(AssertUnwindSafe(|| {
        manager().submit_audio(handle, presentation_us, bytes)
    }));
    result.unwrap_or(SUBMIT_INTERNAL_ERROR)
}

/// `NativeBridge.nativeAudioFailed(handle, detail)`: the audio source failed. Audio stops
/// and the session continues, because audio is an opt-in integration.
unsafe extern "system" fn native_audio_failed(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    detail: jstring,
) {
    let text = read_string(env, detail).unwrap_or_else(|| "audio source failed".to_owned());
    let _ = catch_unwind(|| manager().audio_failed(handle, &text));
}

/// The `offset..offset + size` window of a direct `ByteBuffer`, or `None` when the VM
/// reports no address or the window leaves the buffer.
///
/// # Safety
/// The returned slice is only valid while the caller still owns the buffer, which is the
/// case for the duration of a native method call.
unsafe fn direct_buffer<'a>(
    env: *mut JNIEnv,
    buffer: jobject,
    offset: jint,
    size: jint,
) -> Option<&'a [u8]> {
    if buffer.is_null() || offset < 0 || size <= 0 {
        return None;
    }
    let address = jni!(env, GetDirectBufferAddress, buffer).unwrap_or(null_mut());
    let capacity = jni!(env, GetDirectBufferCapacity, buffer).unwrap_or(-1);
    clear_exception(env);
    let end = (offset as i64).checked_add(size as i64)?;
    if address.is_null() || capacity < 0 || end > capacity {
        return None;
    }
    // SAFETY: the VM reported this address and capacity for the direct buffer, and the
    // slice is read (copied) by the caller before it returns to the VM.
    Some(unsafe {
        std::slice::from_raw_parts(address.cast::<u8>().add(offset as usize), size as usize)
    })
}

/// `NativeBridge.nativeVideoFailed(handle, detail)`: the encoder failed; the session ends
/// with `ENCODER_FAILED`.
unsafe extern "system" fn native_video_failed(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    detail: jstring,
) {
    let text = read_string(env, detail).unwrap_or_else(|| "encoder failed".to_owned());
    let _ = catch_unwind(|| manager().video_failed(handle, &text));
}

/// `NativeBridge.nativeSubmitNowPlaying(handle, state, positionSeconds, sourceApp, title,
/// artist, album)`. Publishes what is playing on the phone for the `media_info` channel
/// (P5.1). Returns 0 (changed), 1 (unchanged), or a negative `NOW_PLAYING_*` code.
///
/// A null string is read as empty rather than refused: an app that knows the state but
/// not the track should still be able to say so. Never blocks on the transport.
unsafe extern "system" fn native_submit_now_playing(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    state: jint,
    position_seconds: jint,
    source_app: jstring,
    title: jstring,
    artist: jstring,
    album: jstring,
) -> jint {
    let (Some(state), false) = (PlaybackState::from_wire(state), position_seconds < 0) else {
        return NOW_PLAYING_INVALID;
    };
    let read = |value: jstring| read_string(env, value).unwrap_or_default();
    let now_playing = NowPlaying::new(
        state,
        position_seconds as u32,
        &read(source_app),
        &read(title),
        &read(artist),
        &read(album),
    );
    let result = catch_unwind(AssertUnwindSafe(|| {
        manager().submit_now_playing(handle, now_playing)
    }));
    result.unwrap_or(NOW_PLAYING_INTERNAL_ERROR)
}

/// `NativeBridge.nativeSubmitGuidance(handle, state, step, maneuver, exit, instruction,
/// onto, road, stepMetres, stepSeconds, destinationMetres, destinationSeconds)`. Publishes
/// the guidance loop's state for a route-driven `navigation` channel (P6.5). The maneuver
/// fields, road names included, are read only for `GUIDANCE_GUIDING`. Returns 0 (changed), 1 (unchanged), or a negative
/// `NOW_PLAYING_*` code. Never blocks on the transport.
#[allow(clippy::too_many_arguments)]
unsafe extern "system" fn native_submit_guidance(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    state: jint,
    step: jlong,
    maneuver: jint,
    exit: jint,
    instruction: jstring,
    onto: jstring,
    road: jstring,
    step_metres: f64,
    step_seconds: jint,
    destination_metres: f64,
    destination_seconds: jint,
) -> jint {
    let state = match state {
        GUIDANCE_IDLE => RouteState::Idle,
        GUIDANCE_REROUTING => RouteState::Rerouting,
        GUIDANCE_ARRIVED => RouteState::Arrived,
        GUIDANCE_ENDED => RouteState::Ended,
        GUIDANCE_GUIDING if step >= 0 => RouteState::Guiding(
            Guidance::live(
                step as usize,
                maneuver,
                Some(exit),
                &read_string(env, instruction).unwrap_or_default(),
                step_metres,
                i64::from(step_seconds),
                destination_metres,
                i64::from(destination_seconds),
            )
            .with_roads(
                &read_string(env, onto).unwrap_or_default(),
                &read_string(env, road).unwrap_or_default(),
            ),
        ),
        _ => return NOW_PLAYING_INVALID,
    };
    let result = catch_unwind(AssertUnwindSafe(|| {
        manager().submit_guidance(handle, state)
    }));
    result.unwrap_or(NOW_PLAYING_INTERNAL_ERROR)
}

/// `NativeBridge.nativeRequestShutdown(handle)`: stop the stream and request shutdown.
/// Returns at once; the session is still released with `nativeStop`.
unsafe extern "system" fn native_request_shutdown(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    let _ = catch_unwind(|| manager().request_shutdown(handle));
}

unsafe fn read_string(env: *mut JNIEnv, value: jstring) -> Option<String> {
    if value.is_null() {
        return None;
    }
    let chars = jni!(env, GetStringUTFChars, value, null_mut()).unwrap_or(std::ptr::null());
    if chars.is_null() {
        clear_exception(env);
        return None;
    }
    let text = CStr::from_ptr(chars).to_string_lossy().into_owned();
    jni!(env, ReleaseStringUTFChars, value, chars);
    Some(text)
}

/// `NativeBridge.nativeGetReport(handle): ByteArray?`. Null for unknown handles.
unsafe extern "system" fn native_get_report(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jbyteArray {
    match catch_unwind(|| manager().report(handle)) {
        Ok(Some(report)) => byte_array(env, &report),
        _ => null_mut(),
    }
}

/// New Java byte array, or null with any exception cleared.
unsafe fn byte_array(env: *mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    let Ok(len) = jint::try_from(bytes.len()) else {
        return null_mut();
    };
    let array = jni!(env, NewByteArray, len).unwrap_or(null_mut());
    if array.is_null() {
        clear_exception(env);
        return null_mut();
    }
    jni!(
        env,
        SetByteArrayRegion,
        array,
        0,
        len,
        bytes.as_ptr().cast()
    );
    if clear_exception(env) {
        jni!(env, DeleteLocalRef, array);
        return null_mut();
    }
    array
}

/// Delivers events to a Kotlin `SessionCallback` from the session worker thread,
/// attaching that thread to the VM on first use and detaching it when dropped.
struct JniEvents {
    vm: *mut JavaVM,
    callback: jobject,
    method: jni_sys::jmethodID,
    attached: bool,
}

// SAFETY: a JavaVM pointer, a global reference, and a method ID are valid on any
// thread; each use obtains the calling thread's own JNIEnv.
unsafe impl Send for JniEvents {}

impl JniEvents {
    unsafe fn new(env: *mut JNIEnv, callback: jobject) -> Option<Self> {
        if callback.is_null() {
            return None;
        }
        let class = jni!(env, GetObjectClass, callback).unwrap_or(null_mut());
        if class.is_null() {
            clear_exception(env);
            return None;
        }
        let method = jni!(
            env,
            GetMethodID,
            class,
            CALLBACK_METHOD.as_ptr(),
            CALLBACK_SIGNATURE.as_ptr()
        )
        .unwrap_or(null_mut());
        jni!(env, DeleteLocalRef, class);
        if method.is_null() {
            clear_exception(env);
            return None;
        }
        let mut vm: *mut JavaVM = null_mut();
        if jni!(env, GetJavaVM, &mut vm) != Some(JNI_OK) || vm.is_null() {
            clear_exception(env);
            return None;
        }
        let callback = jni!(env, NewGlobalRef, callback).unwrap_or(null_mut());
        if callback.is_null() {
            clear_exception(env);
            return None;
        }
        Some(Self {
            vm,
            callback,
            method,
            attached: false,
        })
    }

    /// This thread's environment, or None if it is not attached.
    unsafe fn current_env(&self) -> Option<*mut JNIEnv> {
        let mut env: *mut c_void = null_mut();
        let get_env = (**self.vm).GetEnv?;
        (get_env(self.vm, &mut env, JNI_VERSION_1_6) == JNI_OK).then_some(env as *mut JNIEnv)
    }

    unsafe fn attach(&self) -> Option<*mut JNIEnv> {
        let mut env: *mut c_void = null_mut();
        let attach = (**self.vm).AttachCurrentThread?;
        (attach(self.vm, &mut env, null_mut()) == JNI_OK && !env.is_null())
            .then_some(env as *mut JNIEnv)
    }

    unsafe fn detach(&self) {
        if let Some(detach) = (**self.vm).DetachCurrentThread {
            detach(self.vm);
        }
    }
}

impl Events for JniEvents {
    fn event(&mut self, generation: u64, kind: EventKind, report: &[u8]) -> bool {
        // SAFETY: the VM, global reference, and method ID were validated at creation;
        // all local references are released before returning.
        unsafe {
            let env = match self.current_env() {
                Some(env) => env,
                None => match self.attach() {
                    Some(env) => {
                        self.attached = true;
                        env
                    }
                    None => return false,
                },
            };
            let array = byte_array(env, report);
            if array.is_null() {
                return false;
            }
            let args = [
                jvalue {
                    j: generation as jlong,
                },
                jvalue { i: kind as jint },
                jvalue { l: array },
            ];
            jni!(
                env,
                CallVoidMethodA,
                self.callback,
                self.method,
                args.as_ptr()
            );
            let threw = clear_exception(env);
            jni!(env, DeleteLocalRef, array);
            !threw
        }
    }
}

impl Drop for JniEvents {
    fn drop(&mut self) {
        // SAFETY: as in `event`. A thread this object attached is detached last, after
        // its global reference is released.
        unsafe {
            let (env, temporary) = match self.current_env() {
                Some(env) => (env, false),
                None => match self.attach() {
                    Some(env) => (env, true),
                    None => return,
                },
            };
            jni!(env, DeleteGlobalRef, self.callback);
            if self.attached || temporary {
                self.detach();
            }
        }
    }
}
