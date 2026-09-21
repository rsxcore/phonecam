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
 * This matters more than it looks. A single-buffer version was measured here
 * producing a visible seam about once every 30-60 frames — the copy is long
 * enough relative to the frame interval that the two routinely overlap.
 *
 * This header is shared with Rust, which mirrors the struct by hand. Keep the
 * layout byte-exact: no bools, no pointers, fixed-width types only.
 */

#ifndef PHONECAM_PROTOCOL_H
#define PHONECAM_PROTOCOL_H

#include <stdint.h>

/* Local\ namespace = per logon session. Two users logged in at once each get
 * their own camera instead of fighting over one buffer. */
#define PHONECAM_SHM_NAME L"Local\\PhoneCam_Frame_v1"

/* 'PCAM' — guards against opening somebody else's mapping by accident. */
#define PHONECAM_MAGIC 0x4D414350u

#define PHONECAM_VERSION 1u

/* Pixel layouts. Only BGRA is implemented; the enum exists so the filter can
 * reject a buffer it does not understand instead of drawing garbage. */
typedef enum PhoneCamFormat {
    PHONECAM_FMT_BGRA = 1u /* 32bpp, byte order B,G,R,A — same as DShow RGB32 */
} PhoneCamFormat;

/* The buffer is sized for this at creation and never resized. If the phone
 * switches resolution the header changes, the mapping does not. */
#define PHONECAM_MAX_WIDTH  1920u
#define PHONECAM_MAX_HEIGHT 1080u

/* Two of these: one the consumer may read, one the producer may draw into. */
#define PHONECAM_BUFFERS   2u
#define PHONECAM_BUF_BYTES (PHONECAM_MAX_WIDTH * PHONECAM_MAX_HEIGHT * 4u)

#pragma pack(push, 8)
typedef struct PhoneCamHeader {
    uint32_t magic;      /* PHONECAM_MAGIC */
    uint32_t version;    /* PHONECAM_VERSION */
    uint32_t width;      /* pixels, valid region of the buffer */
    uint32_t height;     /* pixels */
    uint32_t stride;     /* bytes per row; producer may pad it */
    uint32_t format;     /* PhoneCamFormat */
    volatile uint32_t frameIndex; /* seqlock counter; 0 = nothing drawn yet */
    uint32_t activeBuffer;        /* 0..PHONECAM_BUFFERS-1, the complete one */
    uint32_t reserved[8];         /* [0] = GetTickCount heartbeat at publish; rest reserved */
} PhoneCamHeader;
#pragma pack(pop)

#define PHONECAM_PIXELS_OFFSET 64u

/* Buffer n starts here. */
#define PHONECAM_BUF_OFFSET(n) \
    (PHONECAM_PIXELS_OFFSET + (n) * PHONECAM_BUF_BYTES)

#define PHONECAM_SHM_SIZE \
    (PHONECAM_PIXELS_OFFSET + PHONECAM_BUFFERS * PHONECAM_BUF_BYTES)

/* Compile-time proof that the padding above actually lands where the pixel
 * offset says it does. Cheaper to catch here than as a shifted image. */
#if defined(__cplusplus)
static_assert(sizeof(PhoneCamHeader) == PHONECAM_PIXELS_OFFSET,
              "PhoneCamHeader must be exactly PHONECAM_PIXELS_OFFSET bytes");
#else
_Static_assert(sizeof(PhoneCamHeader) == PHONECAM_PIXELS_OFFSET,
               "PhoneCamHeader must be exactly PHONECAM_PIXELS_OFFSET bytes");
#endif

#endif /* PHONECAM_PROTOCOL_H */
