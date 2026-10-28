const PUBLIC_HOST = "dictalab.hoangptkdhy.workers.dev";
const ORIGIN = "https://dictalab-origin-54-254-221-170.sslip.io";

export default {
  async fetch(request) {
    const url = new URL(request.url);
    if (url.hostname !== PUBLIC_HOST) return new Response("Not found", { status: 404 });

    const upstream = new URL(ORIGIN);
    url.protocol = upstream.protocol;
    url.hostname = upstream.hostname;
    url.port = upstream.port;
    const headers = new Headers(request.headers);
    // Replace client-supplied forwarding headers; retain the real browser Origin.
    for (const name of ["Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP"]) {
      headers.delete(name);
    }
    const clientIp = request.headers.get("CF-Connecting-IP");
    if (clientIp) headers.set("X-Forwarded-For", clientIp);
    headers.set("X-Forwarded-Host", PUBLIC_HOST);
    headers.set("X-Forwarded-Proto", "https");
    const outgoing = new Request(url, new Request(request, { headers, redirect: "manual" }));
    const response = await fetch(outgoing, { cache: "no-store" });
    // Returning the original 101 response streams WebSocket frames without buffering.
    if (response.status === 101) return response;
    const location = response.headers.get("Location");
    if (!location) return response;
    const destination = new URL(location, ORIGIN);
    if (destination.origin !== upstream.origin) return response;
    destination.protocol = "https:";
    destination.hostname = PUBLIC_HOST;
    destination.port = "";
    const redirected = new Response(response.body, response);
    redirected.headers.set("Location", destination.href);
    return redirected;
  },
};
