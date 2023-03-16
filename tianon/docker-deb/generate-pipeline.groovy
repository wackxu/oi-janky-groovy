properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		//cron('@daily'),
	]),
])

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'tianon/docker-deb/vars.groovy', // script
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
					logRotator {
						numToKeep(10)
						artifactNumToKeep(3)
					}
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
								}
								scriptPath('tianon/docker-deb/arch-pipeline.groovy')
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
				definition {
					cps {
						script("""
		'''
		for (arch in vars.arches) {
			dsl += """
				stage('Trigger ${arch}') {
					build(
						job: '${arch}',
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

			pipelineJob('source') {
				logRotator {
					numToKeep(10)
					artifactNumToKeep(3)
				}
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
							}
							scriptPath('tianon/docker-deb/source-pipeline.groovy')
						}
					}
				}
				configure {
					it / definition / lightweight(true)
				}
			}

			pipelineJob('repo') {
				description("""
		'''
		for (suite in vars.suites) {
			dsl += """
						<br /><code>deb [ allow-insecure=yes trusted=yes ] https://doi-janky.infosiftr.net/job/tianon/job/docker-deb/job/repo/lastSuccessfulBuild/artifact <strong>${suite}</strong> ${vars.component}</code><br />
			"""
		}
		dsl += '''
				""")
				logRotator {
					numToKeep(10)
					artifactNumToKeep(3)
				}
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
							}
							scriptPath('tianon/docker-deb/repo-pipeline.groovy')
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
