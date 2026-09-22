(async () => {
    const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
    const pageUrl = new URL(window.location.href);
    const level = pageUrl.pathname.split("/").filter(Boolean).at(-1).toUpperCase();
    const lessonPrefix = `/free-resources/listening/${level.toLowerCase()}/`;
    const allLessons = [...document.querySelectorAll("a[href]")]
        .map((anchor) => ({
            title: anchor.textContent.trim(),
            url: new URL(anchor.href, pageUrl).href
        }))
        .filter((lesson) => {
            const url = new URL(lesson.url);
            return lesson.title
                && url.origin === pageUrl.origin
                && url.pathname.startsWith(lessonPrefix)
                && url.pathname !== lessonPrefix;
        })
        .filter((lesson, index, all) => all.findIndex((item) => item.url === lesson.url) === index);
    const startIndex = Number.isInteger(window.__BC_START_INDEX) ? window.__BC_START_INDEX : 0;
    const maxLessons = Number.isInteger(window.__BC_MAX_LESSONS)
        ? window.__BC_MAX_LESSONS
        : allLessons.length;
    const lessons = allLessons.slice(startIndex, startIndex + maxLessons);

    const manifest = {
        source: "British Council LearnEnglish",
        usage: "Downloaded for personal study only",
        level,
        catalogUrl: pageUrl.href,
        downloadedAt: new Date().toISOString(),
        lessons: []
    };

    const saveBlob = async (url, filename) => {
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error(`${response.status} ${response.statusText}`);
        }
        const blob = await response.blob();
        const objectUrl = URL.createObjectURL(blob);
        const anchor = document.createElement("a");
        anchor.href = objectUrl;
        anchor.download = filename;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        await pause(150);
        URL.revokeObjectURL(objectUrl);
        return { size: blob.size, type: blob.type };
    };

    for (const lesson of lessons) {
        const slug = new URL(lesson.url).pathname.split("/").filter(Boolean).at(-1);
        const item = { title: lesson.title, slug, sourceUrl: lesson.url, files: {}, errors: [] };
        try {
            const response = await fetch(lesson.url);
            if (!response.ok) {
                throw new Error(`${response.status} ${response.statusText}`);
            }
            const html = await response.text();
            const doc = new DOMParser().parseFromString(html, "text/html");
            const audioPath = [...doc.querySelectorAll("audio source, audio")]
                .map((element) => element.getAttribute("src"))
                .find(Boolean);
            const worksheetPath = [...doc.querySelectorAll("a[href]")]
                .map((anchor) => anchor.getAttribute("href"))
                .find((href) => href && href.toLowerCase().includes(".pdf"));

            if (audioPath) {
                const audioUrl = new URL(audioPath, lesson.url).href;
                const extension = new URL(audioUrl).pathname.split(".").at(-1).toLowerCase() || "mp3";
                const filename = `${level}-${slug}-audio.${extension}`;
                item.files.audio = { filename, url: audioUrl, ...(await saveBlob(audioUrl, filename)) };
            } else {
                item.errors.push("Audio source not found");
            }

            if (worksheetPath) {
                const worksheetUrl = new URL(worksheetPath, lesson.url).href;
                const filename = `${level}-${slug}-worksheet.pdf`;
                item.files.worksheet = {
                    filename,
                    url: worksheetUrl,
                    ...(await saveBlob(worksheetUrl, filename))
                };
            } else {
                item.errors.push("Worksheet PDF not found");
            }
        } catch (error) {
            item.errors.push(error instanceof Error ? error.message : String(error));
        }
        manifest.lessons.push(item);
    }

    const manifestBlob = new Blob([JSON.stringify(manifest, null, 2)], { type: "application/json" });
    const manifestUrl = URL.createObjectURL(manifestBlob);
    const manifestAnchor = document.createElement("a");
    manifestAnchor.href = manifestUrl;
    manifestAnchor.download = `${level}-manifest-${startIndex + 1}-${startIndex + lessons.length}.json`;
    document.body.appendChild(manifestAnchor);
    manifestAnchor.click();
    manifestAnchor.remove();
    await pause(500);

    return {
        level,
        startIndex,
        lessons: manifest.lessons.length,
        audioFiles: manifest.lessons.filter((lesson) => lesson.files.audio).length,
        worksheets: manifest.lessons.filter((lesson) => lesson.files.worksheet).length,
        errors: manifest.lessons.flatMap((lesson) => lesson.errors.map((error) => `${lesson.slug}: ${error}`))
    };
})()
