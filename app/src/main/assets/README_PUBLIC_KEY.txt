This directory intentionally does not include licensing secrets or model weights.

For an activated release build, copy your existing PUBLIC key only:
SyedShorts/assets/license_public.key -> app/src/main/assets/license_public.key

Do NOT copy private keys, license databases, Cloudflare admin tokens, or user voice samples.
Existing Windows keys are machine-bound. Issue a separate valid key for the Android device ID
using the same Ed25519 admin signing key and unchanged key format.
