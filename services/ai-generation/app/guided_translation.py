"""Source-only round13 handover guidance. Never rewrite source text or generated answers."""
import copy
import re
import unicodedata

DAYS = {'Monday': 'thứ Hai', 'Tuesday': 'thứ Ba', 'Wednesday': 'thứ Tư', 'Thursday': 'thứ Năm',
        'Friday': 'thứ Sáu', 'Saturday': 'thứ Bảy', 'Sunday': 'Chủ nhật'}
PERIODS = {'morning': 'sáng', 'afternoon': 'chiều', 'evening': 'tối', 'night': 'đêm'}
COLOURS = {'white': 'màu trắng', 'black': 'màu đen', 'red': 'màu đỏ', 'brown': 'màu nâu',
    'yellow': 'màu vàng', 'purple': 'màu tím', 'gold': 'màu vàng kim', 'pink': 'màu hồng',
    'grey': 'màu xám', 'gray': 'màu xám', 'orange': 'màu cam'}
IDENTIFIER = r'\b(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*\d)[A-Za-z0-9]+\b'
NEGATION = {'do not': 'đừng', 'must not': 'không được', 'cannot': 'không thể'}
ALIGNMENT_RULE = ('Translate the COMPLETE original utterance and each COMPLETE original fact. These are local phrase constraints, not complete translations. '
    'Keep every local negation and action in its fact. Each time phrase belongs to its own source clause; never substitute another clause time. '
    'Keep fact count and order. Output only the required translationVi and facts schema.')


def in_scope(context):
    # These application scenes concern physical handover. Never map a keyboard/API key by this lexicon.
    return context.get('scenario') in ('delivery', 'Nhận hàng')


def glossary(text):
    terms = {}
    days = '|'.join(DAYS)
    for match in re.finditer(r'\b(by|before|after|until|on)\s+(' + days + r')(?:\s+(morning|afternoon|evening|night))?\b', text, re.I):
        prep, day, period = match.groups()
        date = (PERIODS[period.lower()] + ' ' if period else '') + DAYS[day.title()]
        terms[match.group()] = {'by': 'chậm nhất vào', 'before': 'trước', 'after': 'sau', 'until': 'cho đến', 'on': 'vào'}[prep.lower()] + ' ' + date
    for match in re.finditer(r'\b(' + days + r')(?:\s+(morning|afternoon|evening|night))?\b', text, re.I):
        day, period = match.groups()
        terms[match.group()] = (PERIODS[period.lower()] + ' ' if period else '') + DAYS[day.title()]
    for match in re.finditer(r'\b(?:(older|younger)\s+)?(brother|sister)\b', text, re.I):
        age, noun = match.groups()
        terms[match.group()] = {('older', 'brother'): 'anh trai', ('younger', 'brother'): 'em trai',
            ('older', 'sister'): 'chị gái', ('younger', 'sister'): 'em gái',
            (None, 'brother'): 'anh/em trai', (None, 'sister'): 'chị/em gái'}[age.lower() if age else None, noun.lower()]
    for en, vi in {'collection slip': 'phiếu nhận đồ', 'claim ticket': 'phiếu nhận đồ', 'receipt': 'biên lai',
                   'invoice': 'hóa đơn', 'green': 'xanh lá', 'blue': 'xanh dương', 'silver': 'màu bạc'}.items():
        for match in re.finditer(r'\b' + re.escape(en) + r'\b', text, re.I):
            terms[match.group()] = vi
    return [{'source': en, 'vietnamese': vi} for en, vi in sorted(terms.items(), key=lambda item: (-len(item[0]), item[0]))]


def scoped_terms(text, entries):
    matches = []
    for entry in entries:
        for match in re.finditer(r'\b' + re.escape(entry['source']) + r'\b', text, re.I):
            matches.append((match.start(), match.end(), entry['vietnamese']))
    chosen = []
    for start, end, vi in sorted(set(matches), key=lambda x: (-(x[1] - x[0]), x[0], x[2])):
        if not any(start < e and s < end for s, e, _ in chosen):
            chosen.append((start, end, vi))
    return [{'source': text[s:e], 'vietnamese': vi} for s, e, vi in sorted(chosen)]


def enrich(context):
    c = copy.deepcopy(context)
    sentence = c['utteranceEn']
    whole = sentence + '\n' + '\n'.join(f['textEn'] for f in c['facts'])
    c['translationGlossary'] = glossary(whole)
    c['sentenceRequiredTerms'] = [e['vietnamese'] for e in glossary(sentence)]
    extra = {**COLOURS, 'original receipt': 'biên lai gốc', 'show': 'xuất trình', 'badge': 'huy hiệu'}
    if not re.search(r'\b(keyboard|keystroke|password|api|encryption|cryptographic|computer)\b', whole, re.I):
        extra.update(key='chìa khóa', folder='bìa hồ sơ')
    for en, vi in extra.items():
        if re.search(r'\b' + re.escape(en) + r'\b', whole, re.I):
            c['translationGlossary'].append({'source': en, 'vietnamese': vi})
        if re.search(r'\b' + re.escape(en) + r'\b', sentence, re.I) and vi not in c['sentenceRequiredTerms']:
            c['sentenceRequiredTerms'].append(vi)
    identifiers = [(m.group(), m.group()) for m in re.finditer(IDENTIFIER, whole)]
    entries = identifiers + [(m.group(), vi) for en, vi in NEGATION.items()
        for m in re.finditer(r'\b' + en + r'\b', whole, re.I)]
    for en, vi in entries:
        if not any(e['source'].casefold() == en.casefold() for e in c['translationGlossary']):
            c['translationGlossary'].append({'source': en, 'vietnamese': vi})
        if re.search(r'\b' + re.escape(en) + r'\b', sentence, re.I) and vi not in c['sentenceRequiredTerms']:
            c['sentenceRequiredTerms'].append(vi)
    if re.search(r'\blocker key\b', whole, re.I):
        c['translationGlossary'].append({'source': 'locker key', 'vietnamese': 'chìa khóa tủ'})
    if re.search(r'\blocker key\b', sentence, re.I):
        c['sentenceRequiredTerms'].append('chìa khóa tủ')
    c['sourceTermAlignment'] = {
        'rule': ALIGNMENT_RULE,
        'utteranceClauses': [{'sourceEn': p, 'localGlossary': scoped_terms(p, c['translationGlossary'])}
            for p in re.split(r'(?<=[.!?])\s+|,?\s+but\s+', sentence) if p],
        'facts': [{'index': i, 'sourceEn': f['textEn'], 'localGlossary': scoped_terms(f['textEn'], c['translationGlossary'])}
            for i, f in enumerate(c['facts'])]}
    return c


def validate(context, answer):
    """Targeted terminology audit only. Core still performs full semantic review."""
    def norm(text):
        return unicodedata.normalize('NFC', text).casefold()
    errors = []
    if any(norm(term) not in norm(answer['translationVi']) for term in context['sentenceRequiredTerms']):
        errors.append('translation:missing_supplied_sentence_terms')
    for i, (source, translated) in enumerate(zip(context['facts'], answer['facts'])):
        terms = scoped_terms(source['textEn'], context['translationGlossary'])
        if any(norm(term['vietnamese']) not in norm(translated['textVi']) for term in terms):
            errors.append(f'translation:missing_supplied_fact_terms:{i}')
    for source, translated in [(context['utteranceEn'], answer['translationVi']),
            *((s['textEn'], t['textVi']) for s, t in zip(context['facts'], answer['facts']))]:
        if any(not re.search(r'\b' + re.escape(m.group()) + r'\b', translated) for m in re.finditer(IDENTIFIER, source)):
            errors.append('translation:source_identifier_changed')
    if re.search(r'[\u3400-\u9fff]', answer['translationVi'] + ' '.join(f['textVi'] for f in answer['facts'])):
        errors.append('translation:contains_cjk')
    return list(dict.fromkeys(errors))
