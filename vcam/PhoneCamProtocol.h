/*
 * PhoneCamProtocol.h
 *
 * The contract between the frame producer and the virtual camera filter.
 *
 * Producer  = phonecam-server (Rust). Owns the shared memory, writes frames.
 * Consumer  = PhoneCam.dll (the DirectShow filter). Read-only, never writes.
 *
 * The producer owns the mapping, so the filter can be loaded long before the
 * server starts; it just shows "no signal" until frames show up. That ordering
 * matters because Windows loads the camera filter whenever an app enumerates
 * devices, with no relation to when you happen to launch the server.
 *
 * Version 2 carries upright NV12 frames at their native size (up to 4K) plus
 * the phone's capture timestamp, so the filter can hand apps evenly spaced
 * timestamps even when Wi-Fi delivers frames unevenly.
 *
 * Frame tearing
 * -------------
 * Two pixel buffers and a seqlock counter. The producer draws into the buffer
 * that is *not* active while frameIndex is odd, publishes it by flipping
 * activeBuffer, then makes frameIndex even again. The consumer only copies
 * while frameIndex is even, and re-reads it afterwards: the producer can only
 * reach the buffer being copied after a whole publish cycle, which moves the
 * counter by two, so a counter that has not advanced that far proves the copy
 * was not raced.
 *
 * This header is shared with Rust (server/src/shm.rs), which mirrors the
 * struct by hand. Keep the layout byte-exact: fixed-width types only.
 */

#ifndef PHONECAM_PROTOCOL_H
#define PHONECAM_PROTOCOL_H

#include <stdint.h>

/* Local\ namespace = per logon session. */
#define PHONECAM_SHM_NAME L"Local\\PhoneCam_Frame_v2"

/* 'PCAM' — guards against opening somebody else's mapping by accident. */
#define PHONECAM_MAGIC 0x4D414350u

#define PHONECAM_VERSION 2u

typedef enum PhoneCamFormat {
    PHONECAM_FMT_NV12 = 2u /* Y plane (stride = width) followed by interleaved UV */
} PhoneCamFormat;

#define PHONECAM_MAX_WIDTH  3840u
#define PHONECAM_MAX_HEIGHT 3840u /* portrait 4K is 2160 x 3840 */
#define PHONECAM_MAX_PIXELS (3840u * 2160u)

#define PHONECAM_BUFFERS   2u
#define PHONECAM_BUF_BYTES (PHONECAM_MAX_PIXELS * 3u / 2u)

#pragma pack(push, 8)
typedef struct PhoneCamHeader {
    uint32_t magic;      /* PHONECAM_MAGIC */
    uint32_t version;    /* PHONECAM_VERSION */
    uint32_t width;      /* pixels of the current frame; even */
    uint32_t height;     /* pixels; even */
    uint32_t stride;     /* bytes per Y row; the UV plane uses the same stride */
    uint32_t format;     /* PhoneCamFormat */
    volatile uint32_t frameIndex; /* seqlock counter; 0 = no signal */
    uint32_t activeBuffer;        /* 0..PHONECAM_BUFFERS-1, the complete one */
    uint32_t heartbeat;           /* GetTickCount at the last publish */
    uint32_t fps;                 /* nominal frame rate of the source */
    int64_t ptsUs;                /* capture time of the active frame, phone clock */
    uint32_t reserved[4];
} PhoneCamHeader;
#pragma pack(pop)

#define PHONECAM_PIXELS_OFFSET 64u

#define PHONECAM_BUF_OFFSET(n) \
    ((uint64_t)PHONECAM_PIXELS_OFFSET + (uint64_t)(n) * PHONECAM_BUF_BYTES)

#define PHONECAM_SHM_SIZE \
    ((uint64_t)PHONECAM_PIXELS_OFFSET + (uint64_t)PHONECAM_BUFFERS * PHONECAM_BUF_BYTES)

#if defined(__cplusplus)
static_assert(sizeof(PhoneCamHeader) == PHONECAM_PIXELS_OFFSET,
              "PhoneCamHeader must be exactly PHONECAM_PIXELS_OFFSET bytes");
#else
_Static_assert(sizeof(PhoneCamHeader) == PHONECAM_PIXELS_OFFSET,
               "PhoneCamHeader must be exactly PHONECAM_PIXELS_OFFSET bytes");
#endif

#endif /* PHONECAM_PROTOCOL_H */
