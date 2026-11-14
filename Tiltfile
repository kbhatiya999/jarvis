# Tilt = a dashboard over the workflow. Every resource just runs a mise task,
# so Tilt, your terminal and CI all do the same thing.   Start it: mise run tilt:up

watched = ['app/src', 'app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'fnox.toml', 'fnox.local.toml']

local_resource(
    'env-check',
    cmd='fnox check',
    deps=['fnox.toml', 'fnox.local.toml'],
    labels=['setup'],
)

local_resource(
    'lint',
    cmd='mise run lint',
    deps=watched,
    resource_deps=['env-check'],
    labels=['quality'],
)

local_resource(
    'build',
    cmd='mise run build',
    deps=watched,
    resource_deps=['env-check'],
    labels=['build'],
)

# Manual buttons (click the trigger in the Tilt UI, nothing runs automatically).
local_resource(
    'build-release',
    cmd='mise run build:release',
    trigger_mode=TRIGGER_MODE_MANUAL,
    auto_init=False,
    labels=['release'],
)

local_resource(
    'install-on-device',
    cmd='mise run install',
    trigger_mode=TRIGGER_MODE_MANUAL,
    auto_init=False,
    labels=['device'],
)

local_resource(
    'secrets-push',
    cmd='mise run secrets:push',
    trigger_mode=TRIGGER_MODE_MANUAL,
    auto_init=False,
    labels=['release'],
)
