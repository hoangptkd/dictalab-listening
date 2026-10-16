// Evaluate in a browser via agent-browser eval --stdin; supply temporary credentials,
// never the shared secret. Result contains only connection types and success booleans.
async function verifyTurn(iceServer, expiredCredentials) {
    const gather = async pc => {
        await new Promise(resolve => {
            if (pc.iceGatheringState === "complete") return resolve();
            const timer = setTimeout(resolve, 10000);
            pc.addEventListener("icegatheringstatechange", () => {
                if (pc.iceGatheringState === "complete") { clearTimeout(timer); resolve(); }
            });
        });
    };
    const connect = async transport => {
        const config = {iceTransportPolicy: "relay", iceServers: [{...iceServer, urls: `turn:160.191.244.27:3478?transport=${transport}`} ]};
        const a = new RTCPeerConnection(config), b = new RTCPeerConnection(config);
        let timer;
        try {
            const channel = a.createDataChannel("turn-probe");
            const echoed = new Promise((resolve, reject) => {
                timer = setTimeout(() => reject(new Error("Relay data timeout")), 25000);
                b.ondatachannel = e => { e.channel.onmessage = m => e.channel.send(m.data); };
                channel.onopen = () => channel.send("dictalab-turn-probe");
                channel.onmessage = e => resolve(e.data === "dictalab-turn-probe");
            });
            // Attach rejection handler immediately, while gathering is still in progress.
            echoed.catch(() => {});
            await a.setLocalDescription(await a.createOffer()); await gather(a);
            await b.setRemoteDescription(a.localDescription);
            await b.setLocalDescription(await b.createAnswer()); await gather(b);
            await a.setRemoteDescription(b.localDescription);
            const dataRoundTrip = await echoed;
            const stats = await a.getStats();
            const pair = [...stats.values()].find(s => s.type === "candidate-pair" && s.state === "succeeded" && s.nominated);
            const local = pair && stats.get(pair.localCandidateId);
            const remote = pair && stats.get(pair.remoteCandidateId);
            return {transport, dataRoundTrip, localType: local?.candidateType, remoteType: remote?.candidateType,
                relayPortInRange: local?.port >= 49160 && local?.port <= 49959};
        } finally { clearTimeout(timer); a.close(); b.close(); }
    };
    const rejected = async credentials => {
        const pc = new RTCPeerConnection({iceTransportPolicy: "relay", iceServers: [{...iceServer, ...credentials, urls: "turn:160.191.244.27:3478?transport=udp"}]});
        let relays = 0;
        pc.onicecandidate = e => { if (e.candidate?.type === "relay") relays++; };
        try { pc.createDataChannel("negative-probe"); await pc.setLocalDescription(await pc.createOffer()); await gather(pc); return relays === 0; }
        finally { pc.close(); }
    };
    return {connections: [await connect("udp"), await connect("tcp")],
        badPasswordRejected: await rejected({credential: "deliberately-invalid"}),
        expiredCredentialRejected: await rejected(expiredCredentials)};
}
