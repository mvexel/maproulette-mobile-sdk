#!/usr/bin/env python3
"""Records real responses from the dev backend into fixtures/recorded/.

    scripts/record-fixtures.py            # re-record into fixtures/recorded/
    scripts/record-fixtures.py --check    # re-record into a temp folder and fail on shape drift
    scripts/record-fixtures.py --check --keep   # the same, then replace fixtures/recorded/ (CI)

Each file holds one SDK call: its name and arguments, then the HTTP exchanges it makes, in order,
with the recorded status and body. Both native suites replay every file through the public client
(RecordedTest.kt, RecordedTests.swift), so a response the SDK can no longer decode fails the build.
`--check` compares the shape of every new response (keys and value types) with the committed file.

Only dev (mr-api.osm.lol, OSM dev server) is recorded; dev data is disposable. The guest section
registers a throwaway guest, holds and withdraws one answer, and deletes the guest. Signed-in
sections run only when a token is in the environment and are skipped otherwise:
MR_DEV_ACCESS_TOKEN (an app grant with tasks:read tasks:write) and MR_DEV_ADMIN_TOKEN
(mobile:admin or mobile:organize). Tokens and secrets never reach a file: they are replaced, as are
user ids, OSM user names and guest ids.
"""
import argparse
import json
import os
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ORIGIN = os.environ.get("MR_DEV_ORIGIN", "https://mr-api.osm.lol")
API = "/api/v2/"
CLIENT_ID = "maproulette-ios-choice"
OUT = Path(__file__).resolve().parent.parent / "fixtures" / "recorded"

# Pinned dev data. PLAIN_CHALLENGE has more than one page of 100 tasks (listAllTasks paging);
# CHOICE_CHALLENGE is a type 3 survey with a task that is still open (status 0).
PLAIN_CHALLENGE = 1
CHOICE_CHALLENGE = 5
CHOICE_TASK = 192
PLAIN_TASK = None  # first task of PLAIN_CHALLENGE
BOUNDS = (-111.91, 40.74, -111.87, 40.78)  # west, south, east, north: SLC, covers both challenges

# Keys whose values identify a person or carry a secret.
SECRET_KEYS = {"access_token", "refresh_token", "guestSecret", "guest_secret", "apiKey", "secret", "token"}
PERSON_KEYS = {
    "owner", "completedBy", "reviewRequestedBy", "reviewedBy", "metaReviewedBy", "reviewClaimedBy",
    "userId", "user_id", "osmId", "osmUserId", "guestId", "displayName", "osmUsername", "username",
    "avatarURL", "email", "claimedAs", "lockedBy", "mappedBy",
}


class Scrubber:
    """Replaces people and secrets with stable placeholders of the same JSON type."""

    def __init__(self):
        self.seen = {}

    def value(self, key, value):
        if value is None or isinstance(value, bool):
            return value
        if key in SECRET_KEYS and isinstance(value, str):
            return "recorded-" + key.replace("_", "-") + "-0000"
        if isinstance(value, (dict, list)):
            return self.walk(value, inside_person=True)
        # One user id gets one placeholder whichever field it is in (owner, completedBy, ...).
        ident = ("id", value) if isinstance(value, int) else (key, json.dumps(value))
        n = self.seen.setdefault(ident, len(self.seen) + 1)
        if isinstance(value, int):
            return 900000 + n
        if isinstance(value, float):
            return float(900000 + n)
        if key == "guestId":
            return "00000000-0000-4000-8000-%012d" % n
        if key == "email":
            return "person-%d@example.org" % n if "@" in value else value
        return "person-%d" % n

    def walk(self, node, inside_person=False):
        if isinstance(node, list):
            return [self.walk(item, inside_person) for item in node]
        if isinstance(node, str):
            # Home projects are named after their owner.
            node = re.sub(r"Home_\d+", lambda m: "Home_%d" % self.value("owner", int(m.group(0)[5:])), node)
            return re.sub(r"(Home project for user )\S+", lambda m: m.group(1) + self.value("user", m.group(0)), node)
        if not isinstance(node, dict):
            return node
        out = {}
        for key, value in node.items():
            if key in SECRET_KEYS or key in PERSON_KEYS:
                out[key] = self.value(key, value)
            elif inside_person and key in {"id", "name"} and not isinstance(value, (dict, list)):
                out[key] = self.value("person." + key, value)
            elif key in {"user", "osmProfile"} and isinstance(value, dict):
                out[key] = self.walk(value, inside_person=True)
            else:
                out[key] = self.walk(value, inside_person)
        return out


class Recorder:
    def __init__(self, out):
        self.out = out
        self.scrub = Scrubber()
        self.files = []

    def http(self, method, path, query=None, body=None, form=None, token=None):
        url = ORIGIN + path + ("?" + urllib.parse.urlencode(query) if query else "")
        headers = {"Accept": "application/json", "User-Agent": "maproulette-sdk-fixture-recorder"}
        data = None
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        elif body is not None:
            data = json.dumps(body, separators=(",", ":")).encode()
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        text = raw.decode("utf-8", "replace")
        try:
            parsed = json.loads(text) if text.strip() else None
        except ValueError:
            parsed = text
        return status, parsed

    def exchange(self, method, route, query=None, body=None, token=None, form=None, origin_root=False):
        path = route if origin_root else API + route
        status, parsed = self.http(method, path, query, body, form, token)
        entry = {"method": method, "path": path}
        if query:
            entry["query"] = {k: str(v) for k, v in query.items()}
        if body is not None:
            entry["requestBody"] = body
        entry["status"] = status
        entry["body"] = parsed
        return entry, parsed

    def save(self, name, call, args, exchanges, note, decode=True):
        doc = {
            "_comment": note,
            "recordedFrom": ORIGIN,
            "call": call,
            "args": args,
            "decode": decode,
            "exchanges": self.scrub.walk(exchanges),
        }
        path = self.out / (name + ".json")
        path.write_text(json.dumps(doc, indent=2, ensure_ascii=False, sort_keys=False) + "\n")
        self.files.append(path.name)
        statuses = ",".join(str(e["status"]) for e in exchanges)
        print(f"  {path.name}: {call} -> {statuses}", file=sys.stderr)


def task_query(page, limit):
    return {"limit": limit, "page": page}


def open_choice_tasks(r, limit=60):
    """Open (status 0) type 3 tasks of every enabled dev challenge, at most [limit]."""
    _, challenges = r.http("GET", API + "challenges/extendedFind", {"cLocal": "1", "ce": "true", "limit": "50"})
    found = 0
    for challenge in challenges:
        _, tasks = r.http("GET", API + f"challenge/{challenge['id']}/tasks", task_query(0, 100))
        for task in tasks if isinstance(tasks, list) else []:
            work = task.get("cooperativeWork") or {}
            if task.get("status") == 0 and work.get("questions"):
                found += 1
                yield task
                if found >= limit:
                    return


def box_route():
    return "tasks/box/%s/%s/%s/%s" % BOUNDS


def anonymous(r):
    print("anonymous reads", file=sys.stderr)
    # Queries are exactly what the SDK sends for the recorded call (it drops empty values, like `ct`);
    # the replay tests compare them.
    q = {"cLocal": "1", "ca": "false", "ce": "true", "pe": "true", "sort": "id", "order": "ASC",
         "limit": "10", "page": "0"}
    e, _ = r.exchange("GET", "challenges/extendedFind", q)
    r.save("search-challenges", "searchChallenges", {"pageSize": 10}, [e], "GET challenges/extendedFind, first page of 10.")

    for cid, name in ((CHOICE_CHALLENGE, "choice"), (PLAIN_CHALLENGE, "plain")):
        e, _ = r.exchange("GET", f"challenge/{cid}")
        r.save(f"challenge-{name}", "getChallenge", {"id": cid}, [e], f"GET challenge/:id, the {name} challenge.")
        e, _ = r.exchange("GET", f"challenge/{cid}/tags")
        r.save(f"challenge-tags-{name}", "getChallengeTags", {"id": cid}, [e], "GET challenge/:id/tags.")

    # listTasks paging with a small page size: two full pages, then the page after the last one.
    pages, size = [], 2
    for page in range(3):
        e, rows = r.exchange("GET", f"challenge/{CHOICE_CHALLENGE}/tasks", task_query(page, size))
        pages.append(e)
    r.save("list-tasks-pages", "listTasks", {"id": CHOICE_CHALLENGE, "pageSize": size, "pages": 3}, pages,
           "GET challenge/:id/tasks, three pages of 2 by page number.")

    # listAllTasks: pages of 100, read one at a time until a short page.
    pages = []
    plain_first = None
    for page in range(50):
        e, rows = r.exchange("GET", f"challenge/{PLAIN_CHALLENGE}/tasks", task_query(page, 100))
        pages.append(e)
        if page == 0 and isinstance(rows, list) and rows:
            plain_first = rows[0]["id"]
        if not isinstance(rows, list) or len(rows) < 100:
            break
    r.save("list-all-tasks", "listAllTasks", {"id": PLAIN_CHALLENGE, "concurrency": 1}, pages,
           "listAllTasks over GET challenge/:id/tasks, pages of 100 until a short page.")
    e, rows = r.exchange("GET", f"challenge/{CHOICE_CHALLENGE}/tasks", task_query(0, 100))
    r.save("list-all-tasks-short", "listAllTasks", {"id": CHOICE_CHALLENGE, "concurrency": 1}, [e],
           "listAllTasks on a challenge with fewer than 100 tasks: one short page.")

    e, _ = r.exchange("GET", f"task/{CHOICE_TASK}")
    r.save("task-choice", "getTask", {"id": CHOICE_TASK}, [e], "GET task/:id, a type 3 choice task.")
    plain = PLAIN_TASK or plain_first
    e, _ = r.exchange("GET", f"task/{plain}")
    r.save("task-plain", "getTask", {"id": plain}, [e], "GET task/:id, a plain task.")
    e, _ = r.exchange("GET", "task/999999999")
    r.save("task-missing", "getTask", {"id": 999999999}, [e], "GET task/:id for a task that does not exist.")

    # The app's area query: open choice tasks (default statuses 0, 3, 6, choiceOnly, pending excluded).
    choice = {"cct": "3", "excludeStale": "true", "excludePending": "true"}
    area = {"bounds": list(BOUNDS), "challengeIds": [], "statuses": [0, 3, 6], "choiceOnly": True}
    q = {"cLocal": "1", "ca": "false", "tStatus": "0,3,6", "includeTotal": "true", "sort": "id", "order": "ASC",
         **choice, "limit": "2", "page": "0"}
    first, _ = r.exchange("GET", box_route(), q)
    second, _ = r.exchange("GET", box_route(), dict(q, page="1"))
    r.save("tasks-in-bounds", "findTasksInBounds", dict(area, pageSize=2, pages=2), [first, second],
           "GET tasks/box/w/s/e/n over all challenges, choice tasks only, two pages of 2 with totals.")

    q = {"cLocal": "1", "ca": "false", "tStatus": "0,3,6", "excludeLocked": "true", "limit": "100", **choice,
         "ce": "true", "pe": "true"}
    e, _ = r.exchange("PUT", "markers/box/%s/%s/%s/%s" % BOUNDS, q, body={})
    r.save("task-markers", "findTaskMarkers", dict(area, limit=100), [e],
           "PUT markers/box/w/s/e/n with {}, read-only, choice tasks only.")
    q = {k: v for k, v in q.items() if k not in choice}
    q["tStatus"] = "-1"
    e, _ = r.exchange("PUT", "markers/box/%s/%s/%s/%s" % BOUNDS, q, body={})
    r.save("task-markers-all", "findTaskMarkers",
           {"bounds": list(BOUNDS), "challengeIds": [], "statuses": None, "choiceOnly": False, "limit": 100}, [e],
           "PUT markers/box/w/s/e/n with {}, every status and challenge type.")


def guest(r):
    print("guest", file=sys.stderr)
    status, reg = r.http("POST", "/oauth/mobile/guest", form={"client_id": CLIENT_ID})
    if status != 201:
        sys.exit(f"guest registration failed: HTTP {status} {reg}")
    gid, secret = reg["guestId"], reg["guestSecret"]
    form = {"grant_type": "urn:maproulette:grant-type:guest", "client_id": CLIENT_ID,
            "guest_id": gid, "guest_secret": secret}
    status_token, grant = r.http("POST", "/oauth/mobile/token", form=form)
    r.save("guest-start", "startGuest", {"clientId": CLIENT_ID},
           [{"method": "POST", "path": "/oauth/mobile/guest", "status": status, "body": reg},
            {"method": "POST", "path": "/oauth/mobile/token", "status": status_token, "body": grant}],
           "POST /oauth/mobile/guest, then the guest grant from POST /oauth/mobile/token.")
    if status_token != 200:
        sys.exit(f"guest token failed: HTTP {status_token}")
    token = grant["access_token"]
    try:
        e, _ = r.exchange("GET", "mobile-guest/me", token=token)
        r.save("guest-status-new", "getGuestStatus", {}, [e], "GET mobile-guest/me for a new guest.")

        # Dev data moves (other tests answer tasks, elements change on the OSM dev server), so the
        # task to answer is found each run: the first open choice task the server accepts. The first
        # refusal is kept as the ineligible case.
        held, refused = None, False
        for task in open_choice_tasks(r):
            answers = {q["id"]: q["options"][0]["id"] for q in task["cooperativeWork"]["questions"]}
            body = {"answers": dict(sorted(answers.items()))}
            read, _ = r.exchange("GET", f"task/{task['id']}")
            e, _ = r.exchange("POST", f"task/{task['id']}/choice/pending", body=body, token=token)
            args = {"task": task["id"], "answers": answers}
            if e["status"] < 300:
                held = task["id"]
                r.save("pending-submit", "submitPendingChoice", args, [read, e],
                       "POST task/:id/choice/pending, the first option of every question.")
                break
            if not refused:
                refused = True
                r.save("pending-submit-ineligible", "submitPendingChoice", args, [read, e],
                       "POST task/:id/choice/pending refused: the task can't take this answer.")
        if held is None:
            sys.exit("no open choice task on dev accepted a guest answer")

        e, _ = r.exchange("GET", "mobile-guest/pending", {"limit": "50"}, token=token)
        r.save("pending-list", "listPendingChoices", {"pageSize": 50}, [e], "GET mobile-guest/pending.")
        e, _ = r.exchange("GET", "mobile-guest/me", token=token)
        r.save("guest-status-pending", "getGuestStatus", {}, [e], "GET mobile-guest/me holding one answer.")

        e, _ = r.exchange("DELETE", f"task/{held}/choice/pending", token=token)
        r.save("pending-withdraw", "withdrawPendingChoice", {"task": held}, [e],
               "DELETE task/:id/choice/pending.")
        e, _ = r.exchange("DELETE", "task/999999999/choice/pending", token=token)
        r.save("pending-withdraw-missing", "withdrawPendingChoice", {"task": 999999999}, [e],
               "DELETE task/:id/choice/pending for a task that does not exist.")
    finally:
        e, _ = r.exchange("DELETE", "mobile-guest", token=token)
        r.save("guest-delete", "deleteGuest", {}, [e], "DELETE mobile-guest.")


def signed_in(r, token):
    print("signed in", file=sys.stderr)
    e, _ = r.exchange("GET", "/oauth/mobile/me", token=token, origin_root=True)
    r.save("current-user", "getCurrentUser", {}, [e], "GET /oauth/mobile/me with an app grant.")
    e, _ = r.exchange("GET", f"task/{CHOICE_TASK}/choice/check", token=token)
    r.save("choice-check", "checkChoice", {"task": CHOICE_TASK}, [e], "GET task/:id/choice/check.")


def wizard(r, token):
    print("wizard", file=sys.stderr)
    e, sets = r.exchange("GET", "mobile-admin/wizard/question-sets", token=token)
    r.save("wizard-question-sets", "wizardQuestionSets", {}, [e], "GET mobile-admin/wizard/question-sets.",
           decode=False)
    if isinstance(sets, list) and sets:
        set_id = sets[0].get("id")
        q = {"bbox": ",".join(str(v) for v in BOUNDS)}
        e, _ = r.exchange("GET", f"mobile-admin/wizard/question-sets/{set_id}/features", q, token=token)
        r.save("wizard-features", "wizardFeatures", {"questionSet": set_id}, [e],
               "GET mobile-admin/wizard/question-sets/:id/features.", decode=False)
        e, _ = r.exchange("GET", "mobile-admin/wizard/counts", q, token=token)
        r.save("wizard-counts", "wizardCounts", {}, [e], "GET mobile-admin/wizard/counts.", decode=False)


# Shape comparison: object keys and value types, recursively. null matches anything, and an array's
# shape is the merge of its elements, so data that changes between runs isn't reported as drift.

def shape(node):
    if node is None:
        return None
    if isinstance(node, bool):
        return "boolean"
    if isinstance(node, (int, float)):
        return "number"
    if isinstance(node, str):
        return "string"
    if isinstance(node, list):
        merged = None
        for item in node:
            merged = merge(merged, shape(item))
        return ["array", merged]
    return {key: shape(value) for key, value in node.items()}


def merge(a, b):
    if a is None:
        return b
    if b is None:
        return a
    if isinstance(a, dict) and isinstance(b, dict):
        return {k: merge(a.get(k), b.get(k)) for k in sorted(set(a) | set(b))}
    if isinstance(a, list) and isinstance(b, list):
        return ["array", merge(a[1], b[1])]
    return a if a == b else "mixed"


def drift(old, new, where, out):
    if old is None or new is None:
        return
    if isinstance(old, dict) and isinstance(new, dict):
        for key in sorted(set(old) - set(new)):
            out.append(f"{where}.{key}: gone")
        for key in sorted(set(new) - set(old)):
            out.append(f"{where}.{key}: new")
        for key in sorted(set(old) & set(new)):
            drift(old[key], new[key], f"{where}.{key}", out)
    elif isinstance(old, list) and isinstance(new, list):
        drift(old[1], new[1], where + "[]", out)
    elif old != new:
        out.append(f"{where}: was {describe(old)}, now {describe(new)}")


def describe(s):
    return "object" if isinstance(s, dict) else "array" if isinstance(s, list) else s


def compare(old_dir, new_dir):
    problems = []
    old_files = {p.name for p in old_dir.glob("*.json")}
    new_files = {p.name for p in new_dir.glob("*.json")}
    for name in sorted(old_files - new_files):
        problems.append(f"{name}: not recorded this run")
    for name in sorted(old_files & new_files):
        old = json.loads((old_dir / name).read_text())["exchanges"]
        new = json.loads((new_dir / name).read_text())["exchanges"]
        if [(e["method"], e["status"]) for e in old] != [(e["method"], e["status"]) for e in new]:
            problems.append(f"{name}: statuses {[e['status'] for e in old]} -> {[e['status'] for e in new]}")
            continue
        for i, (a, b) in enumerate(zip(old, new)):
            found = []
            drift(shape(a["body"]), shape(b["body"]), "body", found)
            problems += [f"{name} #{i}: {f}" for f in found]
    for name in sorted(new_files - old_files):
        print(f"  {name}: new file (no committed copy to compare)", file=sys.stderr)
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="record to a temp folder and fail on shape drift")
    parser.add_argument("--keep", action="store_true", help="with --check: then move the new files into place")
    args = parser.parse_args()

    out = Path(tempfile.mkdtemp(prefix="recorded-")) if args.check else OUT
    out.mkdir(parents=True, exist_ok=True)
    if not args.check:
        for old in out.glob("*.json"):
            old.unlink()
    r = Recorder(out)
    anonymous(r)
    guest(r)
    access, admin = os.environ.get("MR_DEV_ACCESS_TOKEN"), os.environ.get("MR_DEV_ADMIN_TOKEN")
    signed_in(r, access) if access else print("signed in: skipped (no MR_DEV_ACCESS_TOKEN)", file=sys.stderr)
    wizard(r, admin) if admin else print("wizard: skipped (no MR_DEV_ADMIN_TOKEN)", file=sys.stderr)

    if not args.check:
        print(f"recorded {len(r.files)} files into {out}", file=sys.stderr)
        return
    skipped = set()
    if not access:
        skipped |= {"current-user.json", "choice-check.json"}
    if not admin:
        skipped |= {p.name for p in OUT.glob("wizard-*.json")}
    for name in skipped:
        if (OUT / name).exists():
            (out / name).write_text((OUT / name).read_text())
    problems = compare(OUT, out)
    if args.keep:
        # The scheduled job then runs both suites on what the backend returns today.
        for old in OUT.glob("*.json"):
            old.unlink()
        for new in out.glob("*.json"):
            (OUT / new.name).write_text(new.read_text())
    if problems:
        print("Shape drift against fixtures/recorded/:", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        print(f"New responses are in {out}. If the change is intended, re-record and update the SDK.",
              file=sys.stderr)
        sys.exit(1)
    print(f"no shape drift across {len(r.files)} recorded files", file=sys.stderr)


if __name__ == "__main__":
    main()
