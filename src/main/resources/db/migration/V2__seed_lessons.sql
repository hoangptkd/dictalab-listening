INSERT INTO lessons (id, slug, title, description, level, topic, estimated_minutes, display_order, active) VALUES
    (1, 'morning-market', 'Morning at the Market', 'Follow Anna through a simple morning shopping trip.', 'A1', 'Daily life', 4, 1, TRUE),
    (2, 'my-small-apartment', 'My Small Apartment', 'Listen for familiar rooms, objects, and locations.', 'A1', 'Home', 4, 2, TRUE),
    (3, 'weekend-train', 'The Weekend Train', 'A short journey with times, places, and observations.', 'A2', 'Travel', 5, 1, TRUE),
    (4, 'dinner-plan', 'Planning Dinner', 'Friends decide what to cook and what they need to buy.', 'A2', 'Food', 5, 2, TRUE),
    (5, 'remote-work-routine', 'A Focused Workday', 'Notice how routines and choices support concentration.', 'B1', 'Work', 6, 1, TRUE),
    (6, 'museum-volunteer', 'The Museum Volunteer', 'A volunteer explains how she helps weekend visitors.', 'B1', 'Community', 6, 2, TRUE),
    (7, 'urban-gardens', 'Gardens Between Buildings', 'Explore how shared gardens change urban neighborhoods.', 'B2', 'Environment', 7, 1, TRUE),
    (8, 'digital-privacy', 'Everyday Digital Privacy', 'Consider practical tradeoffs in modern online services.', 'B2', 'Technology', 7, 2, TRUE);

INSERT INTO lesson_sentences (id, lesson_id, position_index, transcript, audio_url, speech_rate) VALUES
    (1, 1, 1, 'Anna buys fresh apples at the market.', NULL, 0.78),
    (2, 1, 2, 'She talks to a friendly fruit seller.', NULL, 0.78),
    (3, 1, 3, 'Then she walks home before lunch.', NULL, 0.78),
    (4, 2, 1, 'My apartment has a bright kitchen.', NULL, 0.78),
    (5, 2, 2, 'A small table stands beside the window.', NULL, 0.78),
    (6, 2, 3, 'I read there every evening.', NULL, 0.78),

    (7, 3, 1, 'Leo catches an early train to the coast.', NULL, 0.86),
    (8, 3, 2, 'He watches small towns pass the window.', NULL, 0.86),
    (9, 3, 3, 'The journey takes almost two hours.', NULL, 0.86),
    (10, 4, 1, 'Nora invites two friends for dinner.', NULL, 0.86),
    (11, 4, 2, 'They choose a simple vegetable soup.', NULL, 0.86),
    (12, 4, 3, 'Everyone brings one ingredient from home.', NULL, 0.86),

    (13, 5, 1, 'Maya begins her workday by planning three important tasks.', NULL, 0.94),
    (14, 5, 2, 'She turns off notifications to protect her concentration.', NULL, 0.94),
    (15, 5, 3, 'A short afternoon walk helps her return with fresh ideas.', NULL, 0.94),
    (16, 6, 1, 'Daniel volunteers at the local history museum every Saturday.', NULL, 0.94),
    (17, 6, 2, 'He answers questions and guides families through the exhibits.', NULL, 0.94),
    (18, 6, 3, 'The conversations often teach him something unexpected as well.', NULL, 0.94),

    (19, 7, 1, 'Community gardens can transform neglected spaces into productive neighborhoods.', NULL, 1.02),
    (20, 7, 2, 'Residents share responsibility for watering plants and organizing seasonal events.', NULL, 1.02),
    (21, 7, 3, 'Beyond producing food, these gardens strengthen trust among people who rarely interacted before.', NULL, 1.02),
    (22, 8, 1, 'Convenient digital services frequently depend on collecting detailed personal information.', NULL, 1.02),
    (23, 8, 2, 'Users may accept broad permissions without understanding how long their data will be retained.', NULL, 1.02),
    (24, 8, 3, 'Clear explanations allow people to make informed decisions without abandoning useful technology.', NULL, 1.02);
