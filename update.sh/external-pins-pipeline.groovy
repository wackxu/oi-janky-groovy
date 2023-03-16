// properties are set via "generate-pipeline.groovy" (jobDsl)

// This file is intended for updating .external-pins (https://github.com/docker-library/official-images/tree/master/.external-pins).

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'update.sh/vars.groovy', // script
	'https://github.com/wackxu/oi-janky-groovy.git', // repo
	'test', // branch
	null, // credentialsId
	'', // node/label
)
def repo = env.JOB_BASE_NAME
def repoMeta = vars.repoMeta(repo)

node {
	env.repo = repo

	def headCommit = ''
	stage('Checkout') {
		checkout(
			poll: false,
			changelog: false,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [
					[
						name: 'origin',
						url: 'https://github.com/wackxu/official-images.git',
					],
					[
						name: 'fork',
						url: repoMeta['oi-fork'],
						credentialsId: 'docker-library-bot',
					],
				],
				branches: [[name: 'origin/test']],
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

		headCommit = sh(script: 'git -C oi rev-parse HEAD', returnStdout: true).trim()

		sh '''
			git -C oi config user.name 'Docker Library Bot'
			git -C oi config user.email 'github+dockerlibrarybot@infosiftr.com'
		'''

		sshagent(['docker-library-bot']) {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail -x

				cd oi

				# if there's an existing branch for updating external-pins, we should start there to avoid rebasing our branch too aggressively
				if git fetch fork "refs/heads/$repo:" && ! git merge-base --is-ancestor FETCH_HEAD HEAD; then
					# before we go all-in, let's see if master has changes to .external-pins that we *should* aggressively rebase on top of
					touchingCommits="$(git log --oneline 'FETCH_HEAD..HEAD' -- .external-pins)"
					if [ -z "$touchingCommits" ]; then
						git reset --hard FETCH_HEAD
					fi
				fi
			'''
		}
	}

	ansiColor('xterm') { dir('oi') {
		// TODO filter this list by namespace/repo when we split this job
		def tags = sh(
			script: '''
				.external-pins/list.sh
			''',
			returnStdout: true,
		).tokenize()

		for (def tag in tags) { withEnv(['tag=' + tag]) {
			def commit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()

			try {
				stage('Update ' + tag) {
					sh '''#!/usr/bin/env bash
						set -Eeuo pipefail -x

						file="$(.external-pins/file.sh "$tag")"
						before="$(< "$file" || :)"

						.external-pins/update.sh "$tag"

						digest="$(< "$file")"
						[ -n "$digest" ]

						if [ "$before" = "$digest" ]; then
							# bail early without changes
							exit 0
						fi

						# look up image metadata for reproducible commits (GIT_AUTHOR_DATE, GIT_COMMITTER_DATE)
						timestamp="$(
							bashbrew remote arches --json "$tag@$digest" \\
								| jq -r '.arches[][].digest' \\
								| xargs -rI'{}' docker run --rm --security-opt no-new-privileges --user "$RANDOM:$RANDOM" \\
									gcr.io/go-containerregistry/crane@sha256:f0c28591e6b2f5d659cfa3170872675e855851eef4a6576d5663e3d80162b391 \\
									config "$tag@{}" \\
								| jq -r '.created, .history[].created' \\
								| sort -u \\
								| tail -1
						)"
						if [ -n "$timestamp" ]; then
							export GIT_AUTHOR_DATE="$timestamp" GIT_COMMITTER_DATE="$timestamp"
						fi

						git add -A "$file" || :
						git commit -m "Update $tag to $digest" || :
					'''
				}
			}
			catch (err) {
				// if there's an error updating this version, make it like we were never here (and set the build as "unstable")
				withEnv(['commit=' + commit]) {
					sh '''
						git reset --hard "$commit"
						git clean -dffx
					'''
				}
				echo "ERROR while updating ${tag}: ${err}"
				// "catchError" is the only way to set "stageResult" :(
				catchError(message: "ERROR while updating ${tag}: ${err}", buildResult: 'UNSTABLE', stageResult: 'FAILURE') { error() }
			}
		} }

		stage('Push') {
			sshagent(['docker-library-bot']) {
				def newCommit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
				if (newCommit != headCommit) {
					sh 'git push -f fork HEAD:refs/heads/$repo'
				}
				else {
					sh 'git push fork --delete "refs/heads/$repo"'
				}
			}
		}
	} }
}
