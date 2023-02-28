properties([
	buildDiscarder(logRotator(daysToKeepStr: '14')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H H * * *'),
	]),
])

node {
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
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi',
					],
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'PathRestriction',
						excludedRegions: '',
						includedRegions: 'bashbrew/go',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	ansiColor('xterm') {
		stage('Build') {
			sh '''
				docker build -t bashbrew --pull -f oi/bashbrew/Dockerfile.release oi/bashbrew
				rm -rf bin
				docker run -i --rm bashbrew tar -c bin \\
					| tar -xv
			'''
		}
	}

	stage('Archive') {
		archiveArtifacts(
			artifacts: 'bin/*',
			fingerprint: true,
			onlyIfSuccessful: true,
		)
	}
}
