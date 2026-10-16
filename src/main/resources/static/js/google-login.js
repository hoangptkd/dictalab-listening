(() => {
    "use strict";
    let generation = 0, library, busy = false, resize;
    const panel = document.querySelector("#google-auth");
    const button = document.querySelector("#google-signin");
    const status = document.querySelector("#google-auth-status");
    const retry = document.querySelector("#google-auth-retry");

    function loadLibrary() {
        if (window.google?.accounts?.id) return Promise.resolve();
        if (library) return library;
        library = new Promise((resolve, reject) => {
            const script = document.createElement("script");
            script.src = "https://accounts.google.com/gsi/client?hl=vi";
            script.async = true;
            const timeout = setTimeout(failed, 15000);
            function failed() {
                clearTimeout(timeout);
                script.remove();
                library = null;
                reject(new Error("Chưa tải được đăng nhập Google. Bạn có thể thử lại hoặc dùng email."));
            }
            script.onerror = failed;
            script.onload = () => {
                if (!window.google?.accounts?.id) return failed();
                clearTimeout(timeout);
                resolve();
            };
            document.head.appendChild(script);
        });
        return library;
    }

    async function show(api, completeAuthentication, setAuthBusy) {
        if (!panel) return;
        const current = ++generation;
        resize?.disconnect();
        panel.hidden = true;
        button.replaceChildren();
        retry.hidden = true;
        status.textContent = "";
        try {
            const config = await api("/api/auth/google/config", {cache: "no-store", redirectOnUnauthorized: false});
            if (current !== generation || !config.enabled || !config.clientId) return;
            panel.hidden = false;
            status.textContent = "Đang tải đăng nhập Google…";
            await loadLibrary();
            if (current !== generation) return;
            const challenge = await api("/api/auth/google/challenge", {method: "POST", redirectOnUnauthorized: false});
            if (current !== generation) return;
            window.google.accounts.id.initialize({
                client_id: config.clientId, nonce: challenge.nonce, auto_select: false,
                ux_mode: "popup", context: "signin",
                callback: async response => {
                    if (current !== generation || busy || !response.credential || !setAuthBusy(true)) return;
                    busy = true;
                    panel.inert = true;
                    status.textContent = "Đang đăng nhập…";
                    try {
                        const user = await api("/api/auth/google", {
                            method: "POST", body: {credential: response.credential}, redirectOnUnauthorized: false
                        });
                        if (current === generation) await completeAuthentication(user);
                    } catch (error) {
                        if (current === generation) {
                            status.textContent = error.message;
                            button.replaceChildren();
                            retry.hidden = false;
                        }
                    } finally {
                        busy = false;
                        panel.inert = false;
                        setAuthBusy(false);
                    }
                }
            });
            let renderedWidth = 0;
            const render = () => {
                const width = Math.max(200, Math.min(400, Math.floor(panel.clientWidth)));
                if (current !== generation || width === renderedWidth) return;
                renderedWidth = width;
                button.replaceChildren();
                window.google.accounts.id.renderButton(button, {
                    type: "standard", theme: "outline", size: "large", text: "signin_with",
                    shape: "pill", locale: "vi", width
                });
            };
            render();
            if (window.ResizeObserver) {
                resize = new window.ResizeObserver(render);
                resize.observe(panel);
            }
            status.textContent = "";
        } catch (error) {
            if (current !== generation) return;
            // The email form remains usable even when Google or configuration loading fails.
            panel.hidden = false;
            status.textContent = error.message;
            retry.hidden = false;
        }
        retry.onclick = () => show(api, completeAuthentication, setAuthBusy);
    }

    function hide() {
        generation++;
        resize?.disconnect();
        if (panel) panel.hidden = true;
    }
    window.DictaLabGoogleLogin = {show, hide};
})();
