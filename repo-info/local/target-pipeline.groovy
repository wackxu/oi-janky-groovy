// properties are set via "generate-pipeline.groovy" (jobDsl)

def repo = env.JOB_BASE_NAME

lock(label: 'repo-info-local', quantity: 1) { node('repo-info-local') {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

	stage('Checkout') {
		checkout(
			poll: false,
			changelog: false,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'git@github.com:docker-library/repo-info.git',
					credentialsId: 'docker-library-bot',
					name: 'origin',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'ri',
					],
					[
						// this repo is huge and takes a long time to pull 😬
						$class: 'CloneOption',
						depth: 1,
						shallow: true,
						timeout: 90,
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
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

	def tags = []
	stage('Gather') {
		def tagGroups = sh(
			returnStdout: true,
			script: '''#!/usr/bin/env bash
				set -Eeuo pipefail
			''' + """
				repo='${repo}'
			""" + '''
				args=( "$repo" )

				case "$repo" in
					neo4j) # https://github.com/docker-library/official-images/issues/1864 (tag sprawl of ancient, unsupported versions)
						args=( $(bashbrew list "$repo" | grep -E ':[0-9]+[.][0-9]+(-|$)' | xargs bashbrew list --uniq | uniq) )
						# this will narrow down the list to only include "X.Y" and "X.Y-enterprise" tags
						;;
				esac

				bashbrew cat -f '
					{{- range $.Entries -}}
						{{- $arch := .HasArchitecture arch | ternary arch (.Architectures | first) -}}
						{{- if not (hasPrefix "windows-" $arch) -}}
							{{- join " " .Tags -}}
							{{- "\\n" -}}
						{{- end -}}
					{{- end -}}
				' "${args[@]}"
			''',
		).trim().tokenize('\n')
		for (tagGroup in tagGroups) {
			tags << tagGroup.tokenize(' ')
		}
	}

	ansiColor('xterm') { dir('ri') {
		stage('Prepare') {
			sh '''
				gawk '$1 == "FROM" { print $2 }' Dockerfile.local* | xargs -rtn1 docker pull
				sed -i 's/ --pull / /g' scan-local.sh
				! grep -q -- '--pull' scan-local.sh
			'''
		}

		stage('Scan') {
			sh """
				rm -rf 'repos/${repo}/local'
				mkdir -p 'repos/${repo}/local'
			"""

			for (tagGroup in tags) {
				def firstTagName = tagGroup[0]
				def firstTag = repo + ':' + firstTagName
				def firstTarget = "repos/${repo}/local/${firstTagName}.md"
				stage(firstTag) {
					def shells = [
						"""
							firstTag='${firstTag}'
							firstTarget='${firstTarget}'
						""" + '''
							platform="$(bashbrew cat --format '{{ ociPlatform ($.TagEntry.HasArchitecture arch | ternary arch ($.TagEntry.Architectures | first)) }}' "$firstTag")"
							docker pull --platform "$platform" "$firstTag"
							./scan-local.sh "$firstTag" > "$firstTarget"
						''',
					]
					for (int i = 1; i < tagGroup.size(); ++i) {
						def nextTagName = tagGroup[i]
						def nextTarget = "repos/${repo}/local/${nextTagName}.md"
						shells << "cp '${firstTarget}' '${nextTarget}'"
					}
					sh(shells.join('\n'))
				}
			}
		}

		stage('Commit') {
			sh """
				git config user.name 'Docker Library Bot'
				git config user.email 'github+dockerlibrarybot@infosiftr.com'

				git add 'repos/${repo}/local' || :
				git commit -m 'Run scan-local.sh on ${repo}:...' || :
			"""
		}

		def numCommits = sh(
			returnStdout: true,
			script: 'git rev-list --count origin/master...HEAD',
		).trim().toInteger()
		def hasChanges = (numCommits > 0)

		stage('Push') {
			if (hasChanges) {
				sshagent(['docker-library-bot']) {
					sh '''
						# try catching up since this job takes so long to run sometimes
						git checkout -- .
						git clean -dfx .
						git pull --rebase origin master || :

						# fire away!
						git push origin HEAD:master
					'''
				}
			} else {
				echo("No changes in ${repo}!  Skipping.")
			}
		}
	} }
} }
