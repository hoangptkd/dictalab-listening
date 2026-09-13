param(
    [string]$OutputRoot = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $OutputRoot = Join-Path $projectRoot "src/main/resources/static/audio/elllo"
}

$baseUri = [Uri]"https://elllo.org/"
$sourceNotice = "ELLLO (English Listening Lesson Library Online) - https://elllo.org/"
$usageUrl = "https://www.elllo.org/about/faq.htm"

# The ELLLO page currently points conversation 4 of A2-02 at the unrelated
# A2-04 soccer recording. The intended Language recording still exists at this
# URL, so keep the local pack internally consistent until the source page fixes
# its audio tag.
$audioOverrides = @{
    "A2-02-Present-Simple-3rd-Person#4" = "https://elllo.org/Audio/SoundGrammar/A2-Audio4x/A2-02-4-Present-Simple-3rd-Person-Language.mp3"
}

$lessonPages = @(
    @{ Level = "A1"; Page = "https://elllo.org/book/A1/A1-01-Be-Verbs-Introductions.html" },
    @{ Level = "A1"; Page = "https://elllo.org/book/A1/A1-02-Be-Yes-No-Questions.html" },
    @{ Level = "A1"; Page = "https://elllo.org/book/A1/A1-03-Likes-Food.html" },
    @{ Level = "A1"; Page = "https://elllo.org/book/A1/A1-04-Verbs.html" },
    @{ Level = "A1"; Page = "https://elllo.org/book/A1/A1-05-Verb-Yes-No-Questions.html" },

    @{ Level = "A2"; Page = "https://elllo.org/book/A2/A2-01-Present-Simple.html" },
    @{ Level = "A2"; Page = "https://elllo.org/book/A2/A2-02-Present-Simple-3rd-Person.html" },
    @{ Level = "A2"; Page = "https://elllo.org/book/A2/A2-03-Present-Continuous.html" },
    @{ Level = "A2"; Page = "https://elllo.org/book/A2/A2-04-Will.html" },
    @{ Level = "A2"; Page = "https://elllo.org/book/A2/A2-05-Going-To.html" },

    @{ Level = "B1"; Page = "https://elllo.org/book/B1/B1-01-Do-Emphasis.html" },
    @{ Level = "B1"; Page = "https://elllo.org/book/B1/B1-02-Causative-Verbs.html" },
    @{ Level = "B1"; Page = "https://elllo.org/book/B1/B1-03-UsedTo.html" },
    @{ Level = "B1"; Page = "https://elllo.org/book/B1/B1-04-Would-PastTense.html" },
    @{ Level = "B1"; Page = "https://elllo.org/book/B1/B1-05-Should.html" },

    @{ Level = "B2"; Page = "https://elllo.org/book/B2/B2-01-Passive-Simple.html" },
    @{ Level = "B2"; Page = "https://elllo.org/book/B2/B2-02-Past-Passive.html" },
    @{ Level = "B2"; Page = "https://elllo.org/book/B2/B2-03-Was-Able-To.html" },
    @{ Level = "B2"; Page = "https://elllo.org/book/B2/B2-04-Was-Going-To.html" },
    @{ Level = "B2"; Page = "https://elllo.org/book/B2/B2-05-Adverb-Placement.html" }
)

function ConvertFrom-HtmlFragment {
    param([string]$Html)

    $text = $Html -replace '(?i)<br\s*/?>', "`n"
    $text = $text -replace '(?is)<[^>]+>', ''
    $text = [System.Net.WebUtility]::HtmlDecode($text)
    $text = $text.Replace([char]0x00A0, ' ')

    $lines = $text -split "`r?`n" | ForEach-Object {
        ($_ -replace '\s+', ' ').Trim()
    } | Where-Object { $_ }

    return ($lines -join "`n")
}

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$manifestLessons = [System.Collections.Generic.List[object]]::new()

foreach ($lesson in $lessonPages) {
    $level = $lesson.Level
    $pageUrl = $lesson.Page
    Write-Host "Fetching $level lesson page: $pageUrl"

    $response = Invoke-WebRequest -UseBasicParsing -Uri $pageUrl
    $html = $response.Content
    $pageUri = [Uri]$pageUrl
    $lessonId = [IO.Path]::GetFileNameWithoutExtension($pageUri.AbsolutePath)
    $lessonDir = Join-Path (Join-Path $OutputRoot $level) $lessonId
    New-Item -ItemType Directory -Force -Path $lessonDir | Out-Null

    $titleMatch = [regex]::Match($html, '(?is)<h2[^>]*>(.*?)</h2>')
    $title = if ($titleMatch.Success) {
        ConvertFrom-HtmlFragment $titleMatch.Groups[1].Value
    } else {
        $lessonId
    }

    $conversationPattern = '(?is)<h4>\s*Conversation\s+(\d+).*?</h4>.*?<source\s+src="([^"]+\.mp3)"[^>]*>.*?<p>(.*?)</p>'
    $conversationMatches = [regex]::Matches($html, $conversationPattern)
    if ($conversationMatches.Count -lt 1) {
        throw "No conversation clips were found on $pageUrl."
    }
    if ($conversationMatches.Count -ne 4) {
        Write-Warning "$pageUrl contains $($conversationMatches.Count) conversation clips instead of the usual 4."
    }

    $conversations = [System.Collections.Generic.List[object]]::new()
    $transcriptSections = [System.Collections.Generic.List[string]]::new()

    foreach ($match in $conversationMatches) {
        $number = [int]$match.Groups[1].Value
        $remoteAudio = [Uri]::new($pageUri, $match.Groups[2].Value).AbsoluteUri
        $overrideKey = "$lessonId#$number"
        if ($audioOverrides.ContainsKey($overrideKey)) {
            $remoteAudio = $audioOverrides[$overrideKey]
        }
        $remoteFileName = [IO.Path]::GetFileName(([Uri]$remoteAudio).AbsolutePath)
        $localFileName = "conversation-$number-$remoteFileName"
        $localFile = Join-Path $lessonDir $localFileName
        $webPath = "/audio/elllo/$level/$lessonId/$localFileName"
        $transcript = ConvertFrom-HtmlFragment $match.Groups[3].Value

        if ((Test-Path -LiteralPath $localFile) -and ((Get-Item -LiteralPath $localFile).Length -gt 0)) {
            Write-Host "  Keeping existing conversation $number"
        } else {
            Write-Host "  Downloading conversation $number"
            Invoke-WebRequest -UseBasicParsing -Uri $remoteAudio -OutFile $localFile
        }

        $utterances = @($transcript -split "`n" | Where-Object { $_ })
        $conversations.Add([ordered]@{
            number = $number
            audioUrl = $remoteAudio
            localAudioPath = $webPath
            transcript = $transcript
            utterances = $utterances
        })
        $transcriptSections.Add("Conversation $number`n$transcript")
    }

    $lessonMetadata = [ordered]@{
        id = $lessonId
        level = $level
        title = $title
        source = $sourceNotice
        sourcePage = $pageUrl
        usageTerms = $usageUrl
        downloadedAtUtc = [DateTime]::UtcNow.ToString('o')
        conversations = $conversations
    }

    $lessonMetadata | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $lessonDir 'lesson.json')
    ($transcriptSections -join "`n`n") | Set-Content -Encoding utf8 (Join-Path $lessonDir 'transcript.txt')
    $manifestLessons.Add($lessonMetadata)
}

$manifest = [ordered]@{
    collection = "ELLLO Grammar Conversations starter pack"
    source = $sourceNotice
    sourceHome = $baseUri.AbsoluteUri
    usageTerms = $usageUrl
    intendedUse = "Personal study and local educational development. Confirm redistribution rights with ELLLO before public deployment."
    levels = @("A1", "A2", "B1", "B2")
    lessonCount = $manifestLessons.Count
    conversationClipCount = ($manifestLessons | ForEach-Object { $_.conversations.Count } | Measure-Object -Sum).Sum
    lessons = $manifestLessons
}

$manifest | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8 (Join-Path $OutputRoot 'manifest.json')

$notice = @"
# ELLLO starter audio pack

Source: ELLLO (English Listening Lesson Library Online)
Website: https://elllo.org/
Usage information: https://www.elllo.org/about/faq.htm

This local starter pack contains 20 CEFR-labelled Grammar Conversation lessons
(five each for A1, A2, B1, and B2), with four human-recorded conversation clips
per lesson. Every lesson folder includes its source URL and transcript metadata.

ELLLO permits teachers to download available audio for classroom use or a class
LMS. This pack is intended for personal study and local educational development.
Obtain written confirmation from ELLLO before redistributing these files through
a public website or commercial service.
"@
$notice | Set-Content -Encoding utf8 (Join-Path $OutputRoot 'NOTICE.md')

Write-Host "Downloaded $($manifest.lessonCount) lessons and $($manifest.conversationClipCount) conversation clips to $OutputRoot"
