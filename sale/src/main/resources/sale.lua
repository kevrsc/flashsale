local SALE = 'sale'
local PENDING = 'pending'

local function field(name)
	local value = redis.call('HGET', SALE, name)
	if value == false then
		return '0'
	end
	return value
end

local function escape_json(value)
	local text = tostring(value)
	text = string.gsub(text, '\\', '\\\\')
	text = string.gsub(text, '"', '\\"')
	text = string.gsub(text, '[%z\1-\31]', function(char)
		return string.format('\\u%04x', string.byte(char))
	end)
	return text
end

local function bound(value)
	local text = tostring(value)
	return #text .. ':' .. text
end

local function whole(number)
	return string.format('%d', number)
end

local command = ARGV[1]

if command == 'load' then
	if field('open') ~= '0' or field('closed') ~= '0' then
		return '{"applied":false}'
	end
	local itemId = ARGV[2]
	local allotment = ARGV[3]
	if string.match(allotment, '^%d+$') == nil then
		return '{"applied":false}'
	end
	redis.call('HSET', SALE,
		'itemId', itemId,
		'allotment', allotment,
		'remaining', allotment,
		'soldOut', '0',
		'open', '0',
		'closed', '0')
	return '{"applied":true}'
end

if command == 'start' then
	if field('open') ~= '0' or field('closed') ~= '0' then
		return '{"applied":false}'
	end
	if redis.call('HGET', SALE, 'itemId') == false then
		return '{"applied":false}'
	end
	redis.call('HSET', SALE, 'open', '1')
	return '{"applied":true}'
end

if command == 'attempt' then
	local userId = ARGV[2]
	if userId == nil or userId == '' or string.match(userId, '^%s*$') ~= nil then
		return '{"answer":"unidentified"}'
	end
	if field('closed') == '1' then
		return '{"answer":"closed"}'
	end
	if field('open') == '0' then
		return '{"answer":"not_open"}'
	end
	local remaining = field('remaining')
	if field('soldOut') == '1' or remaining == '0' then
		return '{"answer":"sold_out"}'
	end
	local nextRemaining = tonumber(remaining) - 1
	local soldOut = '0'
	if nextRemaining == 0 then
		soldOut = '1'
	end
	redis.call('HSET', SALE, 'remaining', whole(nextRemaining), 'soldOut', soldOut)
	local time = redis.call('TIME')
	local digest = redis.sha1hex(bound(userId) .. bound(remaining) .. bound(time[1]) .. bound(time[2]))
	local hex = string.sub(digest, 1, 32)
	local qualificationId = string.sub(hex, 1, 8)
		.. '-' .. string.sub(hex, 9, 12)
		.. '-' .. string.sub(hex, 13, 16)
		.. '-' .. string.sub(hex, 17, 20)
		.. '-' .. string.sub(hex, 21, 32)
	local itemId = redis.call('HGET', SALE, 'itemId')
	local document = '{"qualificationId":"' .. qualificationId
		.. '","saleId":"sale","userId":"' .. escape_json(userId)
		.. '","itemId":"' .. escape_json(itemId) .. '"}'
	redis.call('HSET', PENDING, qualificationId, document)
	return '{"answer":"qualified","qualificationId":"' .. qualificationId .. '"}'
end

if command == 'close' then
	local remaining = field('remaining')
	local stored = redis.call('HGET', SALE, 'closeRemaining')
	if stored == false then
		redis.call('HSET', SALE, 'closed', '1', 'closeRemaining', remaining)
	else
		remaining = stored
		redis.call('HSET', SALE, 'closed', '1')
	end
	local itemId = redis.call('HGET', SALE, 'itemId')
	if itemId == false then
		itemId = ''
	end
	return '{"saleId":"sale","itemId":"' .. escape_json(itemId) .. '","remaining":' .. whole(tonumber(remaining)) .. '}'
end

if command == 'ack' then
	redis.call('HDEL', PENDING, ARGV[2])
	return '{"applied":true}'
end

return '{"applied":false}'
