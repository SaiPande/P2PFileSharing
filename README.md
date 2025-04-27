# Peer-to-Peer File Sharing System

## Overview
This project implements a decentralized Peer-to-Peer (P2P) File Sharing System, drawing inspiration from BitTorrent. It enables multiple peers to exchange file segments over TCP connections, with features like peer prioritization and dynamic choking/unchoking to optimize performance and fairness.

## Key Features
- **Fully decentralized architecture** utilizing TCP sockets for communication.
- **Choking and unchoking algorithms** to manage bandwidth and prioritize peers based on activity.
- **File chunking** to allow concurrent downloads from multiple peers.
- **Extensive logging** to monitor system events and support debugging.

## Getting Started

### Compilation
To compile the project, run:
```bash
javac *.java
```

### Running the Application
Start each peer with its assigned ID:
```bash
java Peer 1001
java Peer 1002
java Peer 1003
```

### Demo Video
[Watch the system in action](https://uflorida-my.sharepoint.com/personal/saipande_ufl_edu/_layouts/15/stream.aspx?id=%2Fpersonal%2Fsaipande%5Fufl%5Fedu%2FDocuments%2FComputer%20Network%2Emp4&referrer=StreamWebApp%2EWeb&referrerScenario=AddressBarCopied%2Eview%2E17e2a278%2D3f17%2D4e9c%2D8e57%2Dab0c41c0a976)

## Configuration Files

### `Common.cfg`
Defines the global settings for the P2P system:
```
NumberOfPreferredNeighbors 3
UnchokingInterval 5
OptimisticUnchokingInterval 10
FileName tree.jpg
FileSize 24301568
PieceSize 1638400
```

### `PeerInfo.cfg`
Lists all peers with their connection details and initial file ownership:
```
1001 localhost 6003 1
1002 localhost 6004 0
1003 localhost 6005 0
```

## Core Components

- **`refreshPreferredPeers()`**: Dynamically selects preferred neighbors based on their download contribution.
- **`chooseOptimisticPeer()`**: Randomly selects one additional peer to unchoke, promoting fairness.
- **`mergeChunks()`**: Reassembles the original file after all pieces are received.

## Conclusion
This project effectively simulates a scalable and fair file-sharing network. By integrating bitfield tracking and dynamic peer prioritization, it showcases key principles behind modern distributed file-sharing protocols.

## Team Members
**Group 11**
- Sai Pande 
- Asmitha Ramesh

