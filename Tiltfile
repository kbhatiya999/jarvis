load('ext://uibutton', 'cmd_button', 'text_input')

# Tilt is a dashboard over the mise tasks: each resource is a button that runs one task.
# Nothing runs by itself (not on start, save or commit). Press a button in the UI, or run
# `mise run tilt:<resource>` from a terminal. Start it with: mise run tilt:up

config.define_string('version')
cfg = config.parse()
version = cfg.get('version', '')  # set by `mise run tilt:release <x.y.z>`; empty = next patch


def task(name, cmd, label):
    local_resource(name, cmd=cmd, labels=[label], trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False)


# setup
task('env-check', 'fnox check', 'setup')
task('secrets-push', 'mise run secrets:push', 'setup')

# quality / build
task('lint', 'mise run lint', 'quality')
task('build', 'mise run build', 'build')
task('build-release', 'mise run build:release', 'build')

# phone
task('devices', 'mise run devices', 'phone')
task('phone-connect', 'mise run phone:connect', 'phone')
task('install', 'mise run install', 'phone')

# release: local is the normal path; release-ci asks GitHub Actions to do it instead.
task('release', 'mise run release %s' % version, 'release')
task('release-ci', 'mise run release:ci %s' % version, 'release')

# Version box on the release buttons; leave empty for the next patch version.
for res, script in [('release', 'release'), ('release-ci', 'release:ci')]:
    cmd_button(
        name='%s-version' % res,
        resource=res,
        icon_name='rocket_launch',
        text='Release version',
        argv=['sh', '-c', 'mise run %s "$VERSION"' % script],
        inputs=[text_input('VERSION', label='Version (x.y.z)', placeholder='empty = next patch')],
    )
