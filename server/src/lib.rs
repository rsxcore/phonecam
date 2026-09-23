//! PhoneCam receiver: phone connection, hardware decoding and the shared
//! memory handoff to the virtual camera.

pub mod decoder;
pub mod engine;
pub mod identity;
pub mod nv12;
pub mod proto;
pub mod shm;

pub use engine::{Engine, EventSink, VideoPacket, VideoSink};
