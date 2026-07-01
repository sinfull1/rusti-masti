-- wrk script: POST the sample receipt JSON to /render and expect an image/png back.
-- Run wrk from the project root so the relative path resolves.
wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"

local f = assert(io.open("src/main/resources/sample-receipt.json", "r"))
wrk.body = f:read("*a")
f:close()

-- Tally status codes so we can distinguish success (200) from shed load (503).
local status = {}
function response(stat, headers, body)
    status[stat] = (status[stat] or 0) + 1
end

function done(summary, latency, requests)
    io.write("\n--- status code breakdown ---\n")
    for code, count in pairs(status) do
        io.write(string.format("  HTTP %d : %d\n", code, count))
    end
    io.write(string.format("  bytes read: %d\n", summary.bytes))
end