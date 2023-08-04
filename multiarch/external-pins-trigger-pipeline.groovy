// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/wackxu/oi-janky-groovy.git', // repo
	'test', // branch
	null, // credentialsId
	'', // node/label
)

// setup environment variables, etc.
env.ACT_ON_ARCH = env.JOB_NAME.split('/')[-2] // "i386", etc
env.REGISTRY_ADDRESS = ${REGISTRY_ADDRESS}

node(vars.node(env.ACT_ON_ARCH, 'external-pins-trigger')) {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'
	env.BASHBREW_ARCH = env.ACT_ON_ARCH

	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/wackxu/official-images.git',
				]],
				branches: [[name: '*/test']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	dir('oi') {
		pins = sh(returnStdout: true, script: '.external-pins/list.sh').tokenize()

		for (pin in pins) { withEnv(['PIN=' + pin]) {
			// start with a rough check for "does this architecture even *have* this parent?"
			if (0 == sh(returnStatus: true, script: '''#!/usr/bin/env bash
				set -Eeuo pipefail
				set -x
				bashbrew children --arch-filter --depth=-1 "$PIN"
			''')) {
				stage(pin) {
					children = sh(returnStdout: true, script: '''#!/usr/bin/env bash
						set -Eeuo pipefail
						set -x

						bashbrew children --arch-filter --depth 1 "$PIN" \
							| cut -d: -f1 \
							| sort -u
					''').tokenize()

					for (child in children) { withEnv(['CHILD=' + child]) {
						if (0 != sh(returnStatus: true, script: '''#!/usr/bin/env bash
							set -Eeuo pipefail
							set -x
							commit="$(wget -qO- "http://${JENKINS_ADDRESS}/job/multiarch/job/$ACT_ON_ARCH/job/$CHILD/lastSuccessfulBuild/artifact/build-info/commit.txt")"
							[ -n "$commit" ]
							file="$(.external-pins/file.sh "$PIN")"
							[ -s "$file" ]
							dir="$(dirname "$file")"
							file="$(basename "$file")"
							touchingCommits="$(git -C "$dir" log --oneline "$commit...HEAD" -- "$file")"
							[ -z "$touchingCommits" ]
						''')) {
							catchError(stageResult: 'FAILURE') {
								build(
									job: child,
									quietPeriod: 15 * 60, // 15 minutes
									wait: false,
								)

								// also mark the build as unstable so it's obvious which trigger jobs actually triggered builds
								currentBuild.result = 'UNSTABLE'
							}
						}
					} }
				}
			}
		} }
	}
}
