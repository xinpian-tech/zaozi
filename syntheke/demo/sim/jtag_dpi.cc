// SPDX-License-Identifier: Apache-2.0
// The simulation side of the JTAG bridge: one TCP connection, batches of bits in, captured tdo out.
//
// Wire format, both directions little-endian:
//   probe -> simulation : uint32 count, then `count` bytes, bit0 tms, bit1 tdi, bit2 capture
//   simulation -> probe : one byte per captured bit, 0 or 1
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

namespace {

int listen_fd = -1;
int conn_fd = -1;

// The batch being clocked out, and what to send back.
unsigned char *batch = nullptr;
size_t batch_len = 0, batch_pos = 0;
unsigned char *reply = nullptr;
size_t reply_len = 0, reply_cap = 0;
// While idle the simulation asks once per tck period, which would be a syscall per period; only every so many
// actually look at the socket. The wait this adds is a few microseconds of simulated time.
unsigned idle_polls = 0;
const unsigned IDLE_POLL_PERIOD = 64;

void set_nonblocking(int fd) {
  int flags = fcntl(fd, F_GETFL, 0);
  fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

// Read exactly n bytes, blocking: once a batch has started arriving the rest is on its way.
int read_exact(int fd, void *buf, size_t n) {
  unsigned char *p = (unsigned char *)buf;
  while (n > 0) {
    ssize_t got = recv(fd, p, n, 0);
    if (got > 0) {
      p += got;
      n -= got;
    } else if (got == 0) {
      return -1;
    } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
      return -1;
    }
  }
  return 0;
}

void drop_connection() {
  if (conn_fd >= 0) close(conn_fd);
  conn_fd = -1;
  batch_len = batch_pos = 0;
  reply_len = 0;
}

// Take the next batch if the debugger has sent one. Never blocks waiting for a connection or a header.
void poll_batch() {
  if (conn_fd < 0) {
    int fd = accept(listen_fd, nullptr, nullptr);
    if (fd < 0) return;
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    set_nonblocking(fd);
    conn_fd = fd;
    fprintf(stderr, "[JtagDpi] debugger connected\n");
    return;
  }

  uint32_t count = 0;
  ssize_t got = recv(conn_fd, &count, sizeof(count), MSG_PEEK);
  if (got <= 0) {
    if (got == 0) {
      fprintf(stderr, "[JtagDpi] debugger disconnected\n");
      drop_connection();
    }
    return;
  }
  if ((size_t)got < sizeof(count)) return;
  if (read_exact(conn_fd, &count, sizeof(count)) != 0) {
    drop_connection();
    return;
  }
  if (count == 0) return;

  batch = (unsigned char *)realloc(batch, count);
  if (read_exact(conn_fd, batch, count) != 0) {
    drop_connection();
    return;
  }
  batch_len = count;
  batch_pos = 0;
  if (reply_cap < count) {
    reply_cap = count;
    reply = (unsigned char *)realloc(reply, reply_cap);
  }
  reply_len = 0;
}

} // namespace

extern "C" int jtag_dpi_open(int port) {
  listen_fd = socket(AF_INET, SOCK_STREAM, 0);
  if (listen_fd < 0) return -1;
  int one = 1;
  setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  addr.sin_port = htons((uint16_t)port);
  if (bind(listen_fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) return -1;
  if (listen(listen_fd, 1) != 0) return -1;
  set_nonblocking(listen_fd);
  fprintf(stderr, "[JtagDpi] listening on 127.0.0.1:%d\n", port);
  return 0;
}

extern "C" int jtag_dpi_step(int tdo, int *tms, int *tdi) {
  *tms = 0;
  *tdi = 0;

  if (batch_pos == batch_len) {
    // Batch finished: answer it, then look for the next one. Neither step waits on the debugger, so the design
    // keeps running between scans.
    if (batch_len > 0) {
      if (reply_len > 0 && conn_fd >= 0) {
        size_t sent = 0;
        while (sent < reply_len) {
          ssize_t n = send(conn_fd, reply + sent, reply_len - sent, 0);
          if (n > 0) sent += n;
          else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) break;
        }
      }
      batch_len = batch_pos = 0;
      reply_len = 0;
    }
    if (idle_polls++ % IDLE_POLL_PERIOD != 0) return 0;
    poll_batch();
    if (batch_pos == batch_len) return 0;
    idle_polls = 0;
  }

  // The tdo handed over belongs to this bit: the TAP presents it until the edge about to clock the bit out.
  unsigned char bit = batch[batch_pos++];
  *tms = bit & 1;
  *tdi = (bit >> 1) & 1;
  if (bit & 4) reply[reply_len++] = (unsigned char)(tdo & 1);
  return 1;
}
