node {
	git 'https://github.com/wackxu/oi-janky-groovy.git'
	def a = load('wip/test-load.groovy')
	echo(a.test())
	echo(a.b)
}
