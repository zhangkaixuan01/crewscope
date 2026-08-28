package io.crewscope.server.security.login;

import io.crewscope.application.identity.AccountLoginDefenseState;
import io.crewscope.application.identity.AuthenticationFlow;
import io.crewscope.application.identity.LoginDefense;
import io.crewscope.application.identity.LoginDefenseRequest;
import io.crewscope.application.identity.LoginDefenseTelemetry;
import io.crewscope.application.identity.LoginDefenseUnavailableException;
import io.crewscope.application.identity.LoginResourceAdmission;
import io.crewscope.domain.identity.AccountLoginAttemptState;
import io.crewscope.domain.identity.LoginAttemptPolicy;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

/** Atomic Redis implementation of resource admission and known-account temporary locking. */
public final class RedisLoginDefense implements LoginDefense {

    private static final long TIME_REORDER_TOLERANCE_MILLIS = 1_000;
    private static final RedisScript<String> RESOURCE_SCRIPT = new DefaultRedisScript<>(
            """
            local now = tonumber(ARGV[1])
            local tolerance = tonumber(ARGV[2])
            local function last_score(key)
              local item = redis.call('zrange', key, -1, -1, 'WITHSCORES')
              if #item == 0 then return 0 end
              return tonumber(item[2])
            end
            local last = math.max(last_score(KEYS[1]), last_score(KEYS[2]))
            if now + tolerance < last then return 'TIME' end
            local effective = math.max(now, last)
            local identifier_window = tonumber(ARGV[3])
            local network_window = tonumber(ARGV[4])
            redis.call('zremrangebyscore', KEYS[1], '-inf', effective - identifier_window)
            redis.call('zremrangebyscore', KEYS[2], '-inf', effective - network_window)
            local identifier_limited = redis.call('zcard', KEYS[1]) >= tonumber(ARGV[5])
            local network_limited = redis.call('zcard', KEYS[2]) >= tonumber(ARGV[6])
            if identifier_limited and network_limited then return 'BOTH|' .. effective end
            if identifier_limited then return 'IDENTIFIER|' .. effective end
            if network_limited then return 'NETWORK|' .. effective end
            redis.call('zadd', KEYS[1], effective, ARGV[7])
            redis.call('zadd', KEYS[2], effective, ARGV[7])
            redis.call('pexpire', KEYS[1], identifier_window + tolerance + 1000)
            redis.call('pexpire', KEYS[2], network_window + tolerance + 1000)
            return 'ALLOWED|' .. effective
            """,
            String.class);
    private static final RedisScript<String> ACCOUNT_SCRIPT = new DefaultRedisScript<>(
            """
            local now = tonumber(ARGV[1])
            local tolerance = tonumber(ARGV[2])
            local observed = tonumber(redis.call('get', KEYS[3]) or '0')
            if now + tolerance < observed then return 'TIME' end
            local effective = math.max(now, observed)
            local window = tonumber(ARGV[3])
            local failure_limit = tonumber(ARGV[4])
            local lock_duration = tonumber(ARGV[5])
            local retention = tonumber(ARGV[6])
            local operation = ARGV[7]
            local lock_until = tonumber(redis.call('get', KEYS[2]) or '0')
            if lock_until > 0 and lock_until <= effective then
              redis.call('del', KEYS[1], KEYS[2])
              lock_until = 0
            end
            if lock_until == 0 then
              redis.call('zremrangebyscore', KEYS[1], '-inf', effective - window)
              if operation == 'failure' then
                redis.call('zadd', KEYS[1], effective, ARGV[8])
                if redis.call('zcard', KEYS[1]) >= failure_limit then
                  lock_until = effective + lock_duration
                  redis.call('set', KEYS[2], lock_until, 'PX', lock_duration + tolerance + 1000)
                end
              elseif operation == 'success' then
                redis.call('del', KEYS[1], KEYS[2])
              elseif operation ~= 'observe' then
                return 'MALFORMED'
              end
            end
            redis.call('set', KEYS[3], effective, 'PX', retention)
            if redis.call('exists', KEYS[1]) == 1 then redis.call('pexpire', KEYS[1], retention) end
            local values = redis.call('zrange', KEYS[1], 0, -1, 'WITHSCORES')
            local scores = {}
            for index = 2, #values, 2 do table.insert(scores, values[index]) end
            local state = lock_until > effective and 'LOCKED' or 'UNLOCKED'
            return state .. '|' .. effective .. '|' .. lock_until .. '|' .. table.concat(scores, ',')
            """,
            String.class);

    private final ReactiveStringRedisTemplate redis;
    private final LoginDefenseResourceHasher hasher;
    private final LoginDefenseKeyspace keyspace;
    private final LoginAttemptPolicy policy;
    private final Clock clock;
    private final LoginDefenseTelemetry telemetry;

    public RedisLoginDefense(
            ReactiveStringRedisTemplate redis,
            LoginDefenseResourceHasher hasher,
            LoginDefenseKeyspace keyspace,
            LoginAttemptPolicy policy,
            Clock clock,
            LoginDefenseTelemetry telemetry) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Override
    public CompletionStage<LoginResourceAdmission> admit(LoginDefenseRequest request) {
        LoginDefenseRequest required = Objects.requireNonNull(request, "request");
        String flow = required.flow().name().toLowerCase(Locale.ROOT);
        String identifierDigest = hasher.digest(
                flow + ":identifier", required.identifier().canonicalValue());
        String networkDigest = hasher.digest(
                flow + ":network", required.controlledNetwork().canonicalValue());
        List<String> keys = List.of(
                keyspace.identifier(flow, identifierDigest),
                keyspace.network(flow, networkDigest));
        List<String> args = List.of(
                Long.toString(clock.millis()),
                Long.toString(TIME_REORDER_TOLERANCE_MILLIS),
                Long.toString(policy.identifierWindow().toMillis()),
                Long.toString(policy.controlledNetworkWindow().toMillis()),
                Integer.toString(policy.identifierAttemptLimit()),
                Integer.toString(policy.controlledNetworkAttemptLimit()),
                UUID.randomUUID().toString());
        return execute(RESOURCE_SCRIPT, keys, args)
                .map(this::parseAdmission)
                .doOnSuccess(result -> record(
                        required.flow(),
                        LoginDefenseTelemetry.Operation.RESOURCE_ADMISSION,
                        admissionOutcome(result)))
                .doOnError(ignored -> record(
                        required.flow(),
                        LoginDefenseTelemetry.Operation.RESOURCE_ADMISSION,
                        LoginDefenseTelemetry.Outcome.UNAVAILABLE))
                .toFuture();
    }

    @Override
    public CompletionStage<AccountLoginDefenseState> observeAccount(UserAccountId accountId) {
        return account(accountId, "observe", LoginDefenseTelemetry.Operation.ACCOUNT_OBSERVE);
    }

    @Override
    public CompletionStage<AccountLoginDefenseState> recordFailure(UserAccountId accountId) {
        return account(accountId, "failure", LoginDefenseTelemetry.Operation.ACCOUNT_FAILURE);
    }

    @Override
    public CompletionStage<AccountLoginDefenseState> recordSuccess(UserAccountId accountId) {
        return account(accountId, "success", LoginDefenseTelemetry.Operation.ACCOUNT_SUCCESS);
    }

    private CompletionStage<AccountLoginDefenseState> account(
            UserAccountId accountId,
            String operation,
            LoginDefenseTelemetry.Operation metricOperation) {
        UserAccountId required = Objects.requireNonNull(accountId, "accountId");
        String digest = hasher.digest("account", required.value().toString());
        List<String> keys = List.of(
                keyspace.accountFailures(digest),
                keyspace.accountLock(digest),
                keyspace.accountObserved(digest));
        long retention = policy.accountFailureWindow()
                .plus(policy.temporaryLockDuration())
                .plusSeconds(5)
                .toMillis();
        List<String> args = List.of(
                Long.toString(clock.millis()),
                Long.toString(TIME_REORDER_TOLERANCE_MILLIS),
                Long.toString(policy.accountFailureWindow().toMillis()),
                Integer.toString(policy.accountFailureLimit()),
                Long.toString(policy.temporaryLockDuration().toMillis()),
                Long.toString(retention),
                operation,
                UUID.randomUUID().toString());
        return execute(ACCOUNT_SCRIPT, keys, args)
                .map(this::parseAccountState)
                .doOnSuccess(state -> record(
                        AuthenticationFlow.LOGIN,
                        metricOperation,
                        "success".equals(operation)
                                && !state.temporarilyLocked()
                                ? LoginDefenseTelemetry.Outcome.CLEARED
                                : state.temporarilyLocked()
                                        ? LoginDefenseTelemetry.Outcome.LOCKED
                                        : LoginDefenseTelemetry.Outcome.UNLOCKED))
                .doOnError(ignored -> record(
                        AuthenticationFlow.LOGIN,
                        metricOperation,
                        LoginDefenseTelemetry.Outcome.UNAVAILABLE))
                .toFuture();
    }

    private Mono<String> execute(
            RedisScript<String> script, List<String> keys, List<String> arguments) {
        return redis.execute(script, keys, new ArrayList<>(arguments))
                .single()
                .onErrorMap(error -> error instanceof LoginDefenseUnavailableException
                        ? error
                        : new LoginDefenseUnavailableException());
    }

    LoginResourceAdmission parseAdmission(String response) {
        String[] fields = requireResponse(response, 2);
        requireMillis(fields[1]);
        return switch (fields[0]) {
            case "ALLOWED" -> LoginResourceAdmission.ALLOWED;
            case "IDENTIFIER" -> LoginResourceAdmission.IDENTIFIER_RATE_LIMITED;
            case "NETWORK" -> LoginResourceAdmission.NETWORK_RATE_LIMITED;
            case "BOTH" -> LoginResourceAdmission.IDENTIFIER_AND_NETWORK_RATE_LIMITED;
            default -> throw new LoginDefenseUnavailableException();
        };
    }

    AccountLoginDefenseState parseAccountState(String response) {
        String[] fields = requireResponse(response, 4);
        long observedMillis = requireMillis(fields[1]);
        long lockMillis = requireMillis(fields[2]);
        List<UtcTimestamp> failures = new ArrayList<>();
        if (!fields[3].isEmpty()) {
            for (String score : fields[3].split(",", -1)) {
                failures.add(timestamp(requireMillis(score)));
            }
        }
        Optional<UtcTimestamp> lockedUntil = lockMillis == 0
                ? Optional.empty()
                : Optional.of(timestamp(lockMillis));
        boolean expectedLocked = "LOCKED".equals(fields[0]);
        if (!expectedLocked && !"UNLOCKED".equals(fields[0])) {
            throw new LoginDefenseUnavailableException();
        }
        AccountLoginAttemptState domainState;
        try {
            domainState = AccountLoginAttemptState.reconstitute(
                    failures, lockedUntil, timestamp(observedMillis), policy);
        } catch (RuntimeException invalidState) {
            throw new LoginDefenseUnavailableException();
        }
        if (domainState.lockedUntil().isPresent() != expectedLocked) {
            throw new LoginDefenseUnavailableException();
        }
        return AccountLoginDefenseState.from(domainState);
    }

    private static String[] requireResponse(String response, int fields) {
        if (response == null || response.equals("TIME") || response.equals("MALFORMED")) {
            throw new LoginDefenseUnavailableException();
        }
        String[] parsed = response.split("\\|", -1);
        if (parsed.length != fields) {
            throw new LoginDefenseUnavailableException();
        }
        return parsed;
    }

    private static long requireMillis(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new LoginDefenseUnavailableException();
        }
    }

    private static UtcTimestamp timestamp(long millis) {
        return UtcTimestamp.from(Instant.ofEpochMilli(millis));
    }

    private static LoginDefenseTelemetry.Outcome admissionOutcome(LoginResourceAdmission result) {
        return switch (result) {
            case ALLOWED -> LoginDefenseTelemetry.Outcome.ALLOWED;
            case IDENTIFIER_RATE_LIMITED -> LoginDefenseTelemetry.Outcome.IDENTIFIER_LIMITED;
            case NETWORK_RATE_LIMITED -> LoginDefenseTelemetry.Outcome.NETWORK_LIMITED;
            case IDENTIFIER_AND_NETWORK_RATE_LIMITED -> LoginDefenseTelemetry.Outcome.BOTH_LIMITED;
        };
    }

    private void record(
            AuthenticationFlow flow,
            LoginDefenseTelemetry.Operation operation,
            LoginDefenseTelemetry.Outcome outcome) {
        try {
            telemetry.record(flow, operation, outcome);
        } catch (RuntimeException ignored) {
            // Authentication correctness must not depend on the metrics backend.
        }
    }
}
