/**
 * @file
 * @author Edward A. Lee (eal@berkeley.edu)
 *
 * @section LICENSE
Copyright (c) 2020, The University of California at Berkeley.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 * @section DESCRIPTION
 * Runtime infrastructure for distributed Lingua Franca programs.
 * This implementation creates one thread per federate so as to be able
 * to take advantage of multiple cores. It may be more efficient, however,
 * to use select() instead to read from the multiple socket connections
 * to each federate.
 */


#include <stdio.h>
#include <stdlib.h>
#include <errno.h>      // Defined perror(), errno
#include <sys/socket.h>
#include <sys/types.h>  // Provides select() function to read from multiple sockets.
#include <netinet/in.h> // Defines struct sockaddr_in
#include <unistd.h>     // Defines read(), write(), and close()
#include <netdb.h>      // Defines gethostbyname().
#include <strings.h>    // Defines bzero().
#include <pthread.h>
#include "util.c"       // Defines error() and swap_bytes_if_little_endian().
#include "rti.h"        // Defines TIMESTAMP.
#include "reactor.h"    // Defines instant_t.

// The one and only mutex lock.
pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;

// Condition variable used to signal receipt of all proposed start times.
pthread_cond_t received_start_times = PTHREAD_COND_INITIALIZER;

// Array of thread IDs (to be dynamically allocated).
pthread_t* thread_ids;

// Socket descriptors for each federate (to be dynamically allocated).
int* fed_socket_descriptor;

// Number of federates.
int number_of_federates = 0;

// Maximum start time seen so far from the federates.
instant_t max_start_time = 0LL;

// Number of federates that have proposed start times.
int num_feds_proposed_start = 0;

/** Create a server and enable listening for socket connections.
 *  @param port The port number to use.
 *  @return The socket descriptor on which to accept connections.
 */
int create_server(int port) {
    // Create an IPv4 socket for TCP (not UDP) communication over IP (0).
    int socket_descriptor = socket(AF_INET , SOCK_STREAM , 0);
    if (socket_descriptor < 0) error("ERROR on creating RTI socket");

    // Server file descriptor.
    struct sockaddr_in server_fd;
    // Zero out the server address structure.
    bzero((char *) &server_fd, sizeof(server_fd));

    server_fd.sin_family = AF_INET;            // IPv4
    server_fd.sin_addr.s_addr = INADDR_ANY;    // All interfaces, 0.0.0.0.
    // Convert the port number from host byte order to network byte order.
    server_fd.sin_port = htons(port);

    int result = bind(
            socket_descriptor,
            (struct sockaddr *) &server_fd,
            sizeof(server_fd));
    if (result != 0) error("ERROR on binding RTI socket");

    // Enable listening for socket connections.
    // The second argument is the maximum number of queued socket requests,
    // which according to the Mac man page is limited to 128.
    listen(socket_descriptor, 128);

    return socket_descriptor;
}

/** Thread for a federate.
 *  @param fed_socket_descriptor A pointer to an int that is the
 *   socket descriptor for the federate.
 */
void* federate(void* fed_socket_descriptor) {
    int client_socket_descriptor = *((int*)fed_socket_descriptor);

    // Buffer for message ID plus timestamp.
    unsigned char buffer[sizeof(long long) + 1];

    // Read bytes from the socket. We need 9 bytes.
    int bytes_read = 0;
    while (bytes_read < 9) {
        int more = read(client_socket_descriptor, &(buffer[bytes_read]),
                sizeof(long long) + 1 - bytes_read);
        if (more < 0) error("ERROR on RTI reading from socket");
        // If more == 0, this is an EOF. Exit the thread.
        if (more == 0) return NULL;
        bytes_read += more;
    }
    /*
    printf("DEBUG: read %d bytes.\n", bytes_read);
    for (int i = 0; i < sizeof(long long) + 1; i++) {
        printf("DEBUG: received byte %d: %u\n", i, buffer[i]);
    }
    */

    // First byte received in the message ID.
    if (buffer[0] != TIMESTAMP) {
        fprintf(stderr, "ERROR: RTI expected a TIMESTAMP message. Got %u (see rti.h).\n", buffer[0]);
    }

    instant_t timestamp = swap_bytes_if_little_endian_ll(*((long long*)(&(buffer[1]))));
    // printf("DEBUG: RTI received message: %llx\n", timestamp);

    pthread_mutex_lock(&mutex);
    num_feds_proposed_start++;
    if (timestamp > max_start_time) {
        max_start_time = timestamp;
    }
    if (num_feds_proposed_start == number_of_federates) {
        // All federates have proposed a start time.
        pthread_cond_broadcast(&received_start_times);
    } else {
        // Some federates have not yet proposed a start time.
        // wait for a notification.
        while (num_feds_proposed_start < number_of_federates) {
            // FIXME: Should have a timeout here?
            pthread_cond_wait(&received_start_times, &mutex);
        }
    }
    pthread_mutex_unlock(&mutex);

    // Send back to the federate the maximum time.
    // FIXME: Should perhaps increment this time stamp by some amount?
    // Otherwise, the start time will be late by rountrip communication time
    // compared to physical time.

    // Start by sending a timestamp marker.
    unsigned char message_marker = TIMESTAMP;
    int bytes_written = write(client_socket_descriptor, &message_marker, 1);
    // FIXME: Retry rather than exit.
    if (bytes_written < 0) error("ERROR sending message ID to federate");

    // Send the timestamp.
    long long message = swap_bytes_if_little_endian_ll(max_start_time);
    bytes_written = write(client_socket_descriptor, (void*)(&message), sizeof(long long));
    if (bytes_written < 0) error("ERROR sending start time to federate");

    // Nothing more to do. Close the socket and exit.
    close(client_socket_descriptor); //  from unistd.h

    return NULL;
}

/** Wait for one incoming connection request from each federate,
 *  and upon receiving it, create a thread to communicate with
 *  that federate. Return when all federates have connected.
 *  @param number_of_federates The number of federates.
 *  @param socket_descriptor The socket on which to accept connections.
 */
void connect_to_federates(int number_of_federates, int socket_descriptor) {
    // Array of thread IDs.
    thread_ids = malloc(number_of_federates * sizeof(thread_ids));
    // Socket descriptors for each federate.
    fed_socket_descriptor = malloc(number_of_federates * sizeof(int));

    for (int i = 0; i < number_of_federates; i++) {
        // Wait for an incoming connection request.
        struct sockaddr client_fd;
        uint32_t client_length = sizeof(client_fd);
        fed_socket_descriptor[i]
                = accept(socket_descriptor, &client_fd, &client_length);
        if (fed_socket_descriptor < 0) error("ERROR on server accept");

        // Create a thread for the federate.
        pthread_create(&(thread_ids[i]), NULL, federate, &(fed_socket_descriptor[i]));
    }
}

int main(int argc, char* argv[]) {

    // FIXME: Better way to handle port number.
    int socket_descriptor = create_server(55001);

    // FIXME: Better way to handle number of federates.
    number_of_federates = 2;

    // Wait for connections from federates and create a thread for each.
    connect_to_federates(number_of_federates, socket_descriptor);

    // All federates have connected.

    // Wait for federate threads to exit.
    void* thread_exit_status;
    for (int i = 0; i < number_of_federates; i++) {
        pthread_join(thread_ids[i], &thread_exit_status);
        // printf("DEBUG: Federate thread exited.\n");
    }
    close(socket_descriptor);
    free(thread_ids);
    free(fed_socket_descriptor);
}