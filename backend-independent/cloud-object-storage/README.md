# Private object storage SDK adapters

`io.studentcloud.storage.SdkObjectStoreFactory` implements the JDK-only `ObjectStoreFactory` port. This module contains native Aliyun OSS **3.18.5** and AWS S3 SDK **2.57.3** adapters. It has no Spring, database, account/session or legacy ZFile dependency. The content service must approve exact endpoint origins, authorize every operation and enforce workspace namespace and quota before opening it. Operator-approved custom hosts remain trusted infrastructure; this implementation does not pin DNS resolutions.

## Transport and write contract

- Default connection/acquisition timeout: 3 seconds; API/socket timeout: 30 seconds; at most 16 transport connections per open store. The store owns its client and open read streams; close is idempotent.
- Explicit credentials only, including optional session token. No default instance/profile credentials, system/environment HTTP proxy, public URLs, presigners, list-buckets helper, bucket CORS mutation or ACL mutation.
- Endpoint userinfo, path, query and fragment are rejected. HTTP is accepted at this lower boundary for explicitly approved local/private test endpoints; production HTTPS and network address policy belongs to `RemoteEndpointPolicy`. HTTP redirects and S3 cross-region forwarding are disabled.
- Each list call consumes one provider page, with a caller-supplied continuation token and limit 1–1000. It does not iterate the provider paginator.
- S3 create uses `If-None-Match: *`; OSS create uses `x-oss-forbid-overwrite: true`. Every OSS create first performs a full GET of bucket versioning, rejecting Enabled/Suspended/unknown nonempty states. There is no HEAD-before-PUT overwrite fallback and no automatic write replay.
- Size validation enforces 50 MiB at the adapter boundary. The application must still validate the actual input stream, reserve quota, and preserve an uncertain write barrier after ambiguous transport outcomes.
- `createOnly` throws `ObjectStore.WriteFailure`. Validation and pre-PUT failures have `mayHavePublished=false`; known provider rejections (invalid request, authentication, missing target, conditional conflict) also have false. Network failures, timeout, 5xx, redirects and unclassified responses after PUT begins remain true. Public failures contain fixed bounded messages, never SDK exception causes/raw responses.
- Writes include `studentcloud-operation-id`. `head` and `read` expose that value through `Item.operationId` for exact-operation reconciliation. Listing does not claim metadata ownership. Presence/size alone is not proof that an uncertain application operation published an object.
- The S3 SDK signs and Apache transport reopens a body within a single attempt. Non-resettable caller streams are copied to an adapter-owned temporary file before using `RequestBody.fromFile`; this supports zero-byte markers and files beyond the SDK signing read limit without buffering a whole upload in memory. This adds up to 50 MiB of temporary disk I/O per S3 call, on top of application staging. Application upload/probe concurrency budgets must bound aggregate staging. Temp files use POSIX 0600 where available and inherit the user temp directory ACL on Windows; that directory must remain outside browsable mount roots. The adapter validates actual versus declared length before PUT, bounds no-progress reads, never closes the caller's input, and removes its exact temp file on success/failure. A post-PUT temp cleanup failure has its own sanitized code and retains the uncertain publication flag.

## Probe cleanup

A cleanup receipt must identify a strict random v4 UUID key under `.../.studentcloud-probes/<uuid>`. The first create's operation ID must equal this UUID. Only strict reserved probe keys receive `studentcloud-probe=true`; normal uploads never receive this marker.

Cleanup reopens object metadata and verifies the marker, operation UUID, receipt size, ETag and version. It refuses user keys, changed objects or wrong ownership. A versioned receipt deletes the exact version; without a version ID, it checks that versioning is off before attempting deletion. Delete requests also include If-Match. A failed delete returns an explicit cleanup fault, with no automatic replay. The application reports failed or uncertain probe cleanup and its random key to the administrator; probe outcomes currently have no separate durable cleanup queue. This adapter does not recursively discover or delete objects.

Bucket versioning changes or external writers can race application operations. Keep bucket administration and the reserved probe namespace under trusted administrative control. Provider conditional semantics must be verified against each configured endpoint before enabling writes; the loopback tests do not establish compatibility with an arbitrary S3 vendor or a real cloud account.

## Verification

Run from `backend-independent` using Java 21:

```powershell
mvn -pl cloud-object-storage -am test
```

On 2026-10-27 the combined Maven `package` run passed: adapter protocol tests **30 run, 0 failures/errors/skips**; upstream file-kernel tests **67 run, 0 failures/errors, 3 skipped**. The no-progress-read regression is included. No deployment was performed. Existing compiler deprecation notices and the standalone test's missing SLF4J provider warning remain; production logging is supplied by the consuming service.

The protocol suite uses actual SDKs and JDK loopback HttpServer with synthetic credentials. It covers signed requests/session tokens, conditional headers and conflict preservation, one-page continuation, complete OSS version-state rejection, metadata reconciliation, unsafe cleanup/precise version cleanup, deletion failure, timeout/5xx uncertainty and no write retry, redirect refusal, response sanitization, partial read close/connection reuse and store-owned stream cleanup. No real OSS/S3 credential, remote storage CRUD or production deployment was exercised.

SDK source/API evidence:

- [Aliyun OSS 3.18.5 release](https://github.com/aliyun/aliyun-oss-java-sdk/releases/tag/3.18.5) and [3.18.5 ClientConfiguration](https://github.com/aliyun/aliyun-oss-java-sdk/blob/3.18.5/src/main/java/com/aliyun/oss/ClientConfiguration.java).
- [OSS PutObject](https://www.alibabacloud.com/help/en/oss/developer-reference/putobject): forbid-overwrite is ineffective with Enabled/Suspended bucket versioning.
- [AWS PutObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html): conditional-create failure semantics and applicability limits.
- [AWS Java SDK PutObjectRequest.Builder](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/s3/model/PutObjectRequest.Builder.html): current 2.57.3 API exposes `ifNoneMatch`.
