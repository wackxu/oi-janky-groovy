properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		//cron('@daily'),
	]),
])

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'tianon/debuerreotype/vars.groovy', // script
	'https://github.com/wackxu/oi-janky-groovy.git', // repo
	'test', // branch
	null, // credentialsId
	'', // node/label
)

node('built-in') {
	stage('Generate') {
		def dsl = ''

		for (arch in vars.arches) {
			dsl += """
				pipelineJob('${arch}') {
					description('<a href="https://github.com/debuerreotype/docker-debian-artifacts/tree/dist-${arch}"><code>debuerreotype/docker-debian-artifacts</code> @ <code>dist-${arch}</code></a>')
					logRotator {
						numToKeep(10)
						artifactNumToKeep(3)
					}
					// TODO concurrentBuild(false)
					// see https://issues.jenkins-ci.org/browse/JENKINS-31832?focusedCommentId=343307&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-343307
					configure { it / 'properties' << 'org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty' { } }
					parameters {
						stringParam('timestamp', 'today 00:00:00', 'A string that "date(1)" can parse.')
					}
					definition {
						cpsScm {
							scm {
								git {
									remote {
										url('https://github.com/wackxu/oi-janky-groovy.git')
									}
									branch('*/test')
									extensions {
										cleanAfterCheckout()
									}
								}
								scriptPath('tianon/debuerreotype/arch-pipeline.groovy')
							}
						}
					}
					configure {
						it / definition / lightweight(true)
					}
				}
			"""
		}

		dsl += '''
			pipelineJob('_trigger') {
				logRotator { numToKeep(10) }
				// TODO concurrentBuild(false)
				// see https://issues.jenkins-ci.org/browse/JENKINS-31832?focusedCommentId=343307&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-343307
				configure { it / 'properties' << 'org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty' { } }
				parameters {
					stringParam('timestamp', 'today 00:00:00', 'A string that "date(1)" can parse.')
				}
				definition {
					cps {
						script("""
							def vars = fileLoader.fromGit(
								'tianon/debuerreotype/vars.groovy', // script
								'https://github.com/wackxu/oi-janky-groovy.git', // repo
	'test', // branch
								null, // credentialsId
								'', // node/label
							)
							node('built-in') { vars.parseTimestamp(this) }
		'''
		for (arch in vars.arches) {
			dsl += """
				stage('Trigger ${arch}') {
					build(
						job: '${arch}',
						parameters: [
							string(name: 'timestamp', value: env.timestamp),
						],
						wait: false,
					)
				}
			"""
		}
		dsl += '''
						""")
						sandbox()
					}
				}
			}

			pipelineJob('_collector') {
				logRotator { numToKeep(10) }
				// TODO concurrentBuild(false)
				// see https://issues.jenkins-ci.org/browse/JENKINS-31832?focusedCommentId=343307&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-343307
				configure { it / 'properties' << 'org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty' { } }
				definition {
					cpsScm {
						scm {
							git {
								remote {
									url('https://github.com/wackxu/oi-janky-groovy.git')
								}
								branch('*/test')
								extensions {
									cleanAfterCheckout()
								}
							}
							scriptPath('tianon/debuerreotype/collector-pipeline.groovy')
						}
					}
				}
				configure {
					it / definition / lightweight(true)
				}
			}
		'''

		jobDsl(
			lookupStrategy: 'SEED_JOB',
			removedJobAction: 'DELETE',
			removedViewAction: 'DELETE',
			scriptText: dsl,
		)
	}
}
