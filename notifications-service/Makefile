PHONY: pull-notifications-chart deploy-notifications deploy-notifications-local

env := "dev"

# this will go away once we/someone creates an s3 plugin for helm
pull-notifications-chart:
	aws s3 cp s3://charthouse/notifications-0.0.1.tgz bin/

deploy-notifications: pull-notifications-chart
	helmfile\
		--file charts.yaml\
		--kube-context $(env).etl.pennsieve.io\
		charts\
		--values $(env).values.yaml

deploy-notifications-local: pull-notifications-chart
	helmfile\
		--file charts.local.yaml\
		--kube-context minikube\
		charts