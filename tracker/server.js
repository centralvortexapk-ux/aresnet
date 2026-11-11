/**
 * AresNet P2P Tracker & Rendezvous Server
 * High-performance, memory-efficient peer discovery node.
 * Stores zero copyrighted files; acts exclusively as a temporary IP directory.
 */
const express = require('express');
const cors = require('cors');

const app = express();
app.use(cors());
app.use(express.json());

// In-memory registries:
// swarmRegistry: Map<fileHash: string, Set<peerAddress: string>>
const swarmRegistry = new Map();
// activeNodes: Map<nodeId: string, { peerAddress: string, lastSeen: number, hashes: Set<string> }>
const activeNodes = new Map();
// Blocked DMCA / illegal hashes set
const blockedHashes = new Set([
    "0000000000000000000000000000000000000000000000000000000000000000"
]);

// 1. Health check & status
app.get('/health', (req, res) => {
    res.json({
        status: "ONLINE",
        service: "AresNet Global P2P Tracker",
        version: "1.0.0",
        activeNodesCount: activeNodes.size,
        totalSwarmsCount: swarmRegistry.size,
        uptimeSeconds: Math.floor(process.uptime())
    });
});

// 2. Announce node presence & files being seeded
app.post('/api/p2p/announce', (req, res) => {
    const { nodeId, port, sharedHashes, clientVersion } = req.body;

    if (!nodeId || !port) {
        return res.status(400).json({ error: "Missing required fields: nodeId, port" });
    }

    // Detect real public WAN IP address of the peer
    const forwarded = req.headers['x-forwarded-for'];
    const clientIp = forwarded ? forwarded.split(',')[0].trim() : req.socket.remoteAddress;
    const sanitizedIp = clientIp.replace('::ffff:', '');
    const peerAddress = `${sanitizedIp}:${port}`;

    const validHashes = new Set();
    if (Array.isArray(sharedHashes)) {
        sharedHashes.forEach(h => {
            if (typeof h === 'string' && h.length === 64 && !blockedHashes.has(h)) {
                validHashes.add(h);
                if (!swarmRegistry.has(h)) {
                    swarmRegistry.set(h, new Set());
                }
                swarmRegistry.get(h).add(peerAddress);
            }
        });
    }

    activeNodes.set(nodeId, {
        peerAddress,
        lastSeen: Date.now(),
        hashes: validHashes,
        clientVersion: clientVersion || "AresNet/1.0"
    });

    console.log(`[Announce] Node ${nodeId} at ${peerAddress} sharing ${validHashes.size} hashes`);

    res.json({
        status: "OK",
        detectedPublicIp: sanitizedIp,
        publicPort: port,
        peerAddress,
        intervalSeconds: 60,
        swarmsRegistered: validHashes.size
    });
});

// 3. Query peer seeders for a given file hash
app.get('/api/p2p/peers', (req, res) => {
    const hash = req.query.hash;
    if (!hash) {
        return res.status(400).json({ error: "Query parameter 'hash' is required" });
    }

    if (blockedHashes.has(hash)) {
        return res.status(403).json({ error: "Hash blocked by DMCA/policy moderation", seeders: [] });
    }

    const peersSet = swarmRegistry.get(hash);
    const seeders = peersSet ? Array.from(peersSet) : [];

    res.json({
        hash,
        seedersCount: seeders.length,
        seeders
    });
});

// 3.1. Query all active file hashes registered in the network
app.get('/api/p2p/hashes', (req, res) => {
    const hashes = Array.from(swarmRegistry.keys());
    res.json({
        hashesCount: hashes.length,
        hashes
    });
});

// 4. Report inappropriate or copyright-infringing hash
app.post('/api/p2p/report', (req, res) => {
    const { hash, reason } = req.body;
    if (hash && hash.length === 64) {
        blockedHashes.add(hash);
        swarmRegistry.delete(hash);
        console.warn(`[Moderation] Hash ${hash} blocked. Reason: ${reason || "Unspecified"}`);
        return res.json({ status: "BLOCKED", hash });
    }
    res.status(400).json({ error: "Invalid hash format" });
});

// 5. Periodic cleanup of stale nodes (heartbeat expiration: 90 seconds)
setInterval(() => {
    const now = Date.now();
    const staleThreshold = 90000; // 90 seconds
    for (const [nodeId, data] of activeNodes.entries()) {
        if (now - data.lastSeen > staleThreshold) {
            console.log(`[Pruning] Node ${nodeId} timed out (${data.peerAddress})`);
            activeNodes.delete(nodeId);
            // Remove peer from all swarm sets
            for (const hash of data.hashes) {
                const set = swarmRegistry.get(hash);
                if (set) {
                    set.delete(data.peerAddress);
                    if (set.size === 0) swarmRegistry.delete(hash);
                }
            }
        }
    }
}, 30000);

const PORT = process.env.PORT || 3000;
app.listen(PORT, '0.0.0.0', () => {
    console.log(`===============================================`);
    console.log(` AresNet P2P Tracker is listening on port ${PORT}`);
    console.log(` Health: http://localhost:${PORT}/health`);
    console.log(`===============================================`);
});
