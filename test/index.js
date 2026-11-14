// Entry point so that `node --test trigger/test/` works on Node 22, which
// resolves a directory argument to its index.js instead of scanning it.
// `node --test "trigger/test/*.test.js"` runs the same files one by one.

import './vectors.test.js';
import './protocol.test.js';
import './ts.test.js';
import './flow.test.js';
import './relay.test.js';
import './page.test.js';
