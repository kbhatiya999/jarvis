load('ext://uibutton', 'cmd_button', 'text_input')

# The Jarvis workflow, as seen in Tilt (http://localhost:10350).
#
#   steps      one resource per job; each runs one hidden mise task
#   pipelines  press steps in order and stop at the first failure (release)
#
# There is one way to make an APK: the build step (scripts/build.sh, signed with the release
# key). release and install use it, and so do the GitHub workflows (Release, Build) for when
# the Mac isn't around, so every copy on any phone can update any other.
#
# Nothing runs by itself: not on start, save or commit. Press a button here, or run
# `mise run tilt:<name>` in a terminal: it presses the same button, streams the log and
# waits for the result. Resources allow parallel runs, otherwise a pipeline would block the
# steps it presses. Values you type (version, pairing code) go to .tilt-inputs/.


# Each run's output is also written to .tilt-inputs/logs/<name>.log, which tilt:* tasks stream.
def logged(name, cmd):
    return 'set -o pipefail; mkdir -p .tilt-inputs/logs; (%s) 2>&1 | tee .tilt-inputs/logs/%s.log' % (cmd, name)


def step(name, task, label):
    local_resource(name, cmd=logged(name, 'mise run -q %s' % task), labels=[label],
                   trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False, allow_parallel=True)


def pipeline(name, steps):
    local_resource(name, cmd=logged(name, 'scripts/tilt-run.sh ' + ' '.join(steps)), labels=['pipelines'],
                   trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False, allow_parallel=True)


# ---- steps
step('setup', 'setup', 'setup')
step('env-check', 'env:check', 'setup')
step('keystore-gen', 'keystore:gen', 'setup')
step('secrets-push', 'secrets:push', 'setup')

step('lint', 'lint', 'build')
step('test', 'test', 'build')
step('build', 'build', 'build')
step('build-ci', 'build:ci', 'build')  # same build, on GitHub Actions

step('devices', 'devices', 'phone')
step('phone-pair', 'phone:pair', 'phone')
step('phone-connect', 'phone:connect', 'phone')
step('install', 'install', 'phone')  # dev build -> adb install

step('release-check', 'release:check', 'release')
step('release-publish', 'release:publish', 'release')
step('release-ci', 'release:ci', 'release')  # the release pipeline, on GitHub Actions

# ---- pipelines
# release: everything happens on this Mac; GitHub only stores the release for Obtainium.
pipeline('release', ['release-check', 'lint', 'test', 'build', 'release-publish'])

# ---- buttons with input boxes
cmd_button(
    name='release-with-version', resource='release', icon_name='rocket_launch', text='Release version',
    argv=['sh', '-c', 'scripts/tilt-input.sh version "$VERSION" && tilt trigger release'],
    inputs=[text_input('VERSION', label='Version (x.y.z)', placeholder='empty = next patch')],
)
cmd_button(
    name='release-ci-with-version', resource='release-ci', icon_name='cloud_upload', text='Release version on GitHub',
    argv=['sh', '-c', 'scripts/tilt-input.sh version "$VERSION" && tilt trigger release-ci'],
    inputs=[text_input('VERSION', label='Version (x.y.z)', placeholder='empty = next patch')],
)
cmd_button(
    name='pair-with-code', resource='phone-pair', icon_name='link', text='Pair',
    argv=['sh', '-c', 'scripts/tilt-input.sh pair-address "$ADDRESS" && scripts/tilt-input.sh pair-code "$CODE" && tilt trigger phone-pair'],
    inputs=[
        text_input('ADDRESS', label='IP:port (pairing dialog)', placeholder='192.168.1.23:37115'),
        text_input('CODE', label='Pairing code', placeholder='123456'),
    ],
)
