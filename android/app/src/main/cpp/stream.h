/**
 * @file src/stream.h
 * @brief Declarations for the streaming protocols.
 */
#pragma once

// standard includes
#include <utility>

// lib includes
#include <boost/asio.hpp>

// local includes
#include "audio.h"
#include "crypto.h"
#include "video.h"
#include "rtsp.h"

namespace stream {
  constexpr auto VIDEO_STREAM_PORT = 9;
  constexpr auto CONTROL_PORT = 10;
  constexpr auto AUDIO_STREAM_PORT = 11;

  struct session_t;

  struct config_t {
    audio::config_t audio;
    video::config_t monitor;

    int packetsize;
    int minRequiredFecPackets;
    int mlFeatureFlags;
    int controlProtocolType;
    int audioQosType;
    int videoQosType;

    uint32_t encryptionFlagsEnabled;

    std::optional<int> gcmap;
  };

  void postFrame(std::vector<uint8_t> &&frame_data, int64_t frame_index, bool idr, void* channel_data);

  /**
   * @brief Keep the UDP broadcast listeners alive for the process lifetime.
   *
   * Without this the listeners are destroyed as soon as the last session
   * reference drops, and a reconnect has to rebind control/video/audio ports
   * while the old sockets are still closing, so the client times out during
   * the control-stream handshake.
   */
  void pin_broadcast();

  /** Drop the pinned broadcast reference so a restart can rebind its ports. */
  void unpin_broadcast();

  namespace session {
    enum class state_e : int {
      STOPPED,  ///< The session is stopped
      STOPPING,  ///< The session is stopping
      STARTING,  ///< The session is starting
      RUNNING,  ///< The session is running
    };

    std::shared_ptr<session_t> alloc(config_t &config, rtsp_stream::launch_session_t &launch_session);
    int start(session_t &session, const std::string &addr_string);
    void stop(session_t &session);
    void join(session_t &session);
    state_e state(session_t &session);
  }  // namespace session
}  // namespace stream