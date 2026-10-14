(() => {
    "use strict";
    const links = [...document.querySelectorAll('.policy-nav a[href^="#"]')];
    function markSection() {
        const section = location.hash || `#${location.pathname.slice(1)}`;
        links.forEach(link => {
            if (link.hash === section) link.setAttribute("aria-current", "location");
            else link.removeAttribute("aria-current");
        });
        return links.find(link => link.hash === section)?.hash.slice(1);
    }
    window.addEventListener("hashchange", markSection);
    const initialSection = markSection();
    if (!location.hash && initialSection) document.getElementById(initialSection)?.scrollIntoView();
    const contacts = [...document.querySelectorAll("#public-contact, [data-public-contact]")];
    fetch("/api/public/info", {credentials: "omit"})
        .then(response => {if (!response.ok) throw new Error("Contact unavailable"); return response.json();})
        .then(info => {
            if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(info.supportEmail)) throw new Error("Contact unavailable");
            contacts.forEach(contact => {
                const link = document.createElement("a");
                link.href = `mailto:${info.supportEmail}`;
                link.textContent = info.supportEmail;
                contact.replaceChildren(document.createTextNode("Liên hệ khi không đăng nhập được: "), link);
            });
        }).catch(() => {contacts.forEach(contact => {contact.textContent = "Chưa tải được địa chỉ liên hệ. Vui lòng thử lại sau.";});});
})();
