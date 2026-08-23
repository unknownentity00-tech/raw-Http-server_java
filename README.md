Custom HTTP Server in Java — A from-scratch HTTP server built using Java networking and NIO, implementing TCP connections, HTTP request parsing, routing, response generation, concurrency, keep-alive connections, error handling, and request validation across six development phases.

 the prooiject has been divided into  6 pahses 

Project Phases
Phase 1 — Basic HTTP Server: Built a basic TCP server that accepts client connections, reads raw HTTP requests, detects HTTP headers, and sends responses.
Phase 2 — HTTP Parsing: Added structured request parsing for methods, paths, HTTP versions, headers, and request bodies.
Phase 3 — Routing: Implemented a routing system that maps HTTP methods and paths to application handlers, including 404, 405, HEAD, and OPTIONS.
Phase 4 — NIO Architecture: Migrated from blocking I/O to Java NIO using Selector, SocketChannel, and non-blocking OP_READ/OP_WRITE operations.
Phase 5 — HTTP Robustness: Added persistent connection handling, request buffering, pipelining support, Content-Length handling, HEAD responses, centralized error responses, connection lifecycle management, and graceful shutdown.
Phase 6 — Production Hardening: Added strict HTTP validation, request/header/body size limits, duplicate-header protection, header-name/value validation, Host validation, unsupported Transfer-Encoding rejection, safer routing, deterministic response headers, and improved connection/request state management.


PHASE 1-

<img width="700" height="408" alt="Screenshot 2026-08-23 at 17 00 22" src="https://github.com/user-attachments/assets/4e3b21be-4525-45b8-b211-24b4ba90c4e4" />

Built the foundational HTTP server using Java ServerSocket and InputStream. Implemented TCP connection handling, byte buffering, request accumulation, detection of the \r\n\r\n HTTP header boundary, basic HTTP request parsing, and generation of HTTP responses.


  
