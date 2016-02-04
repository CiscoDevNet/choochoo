(function(angular) {

    // defining angular module 'choochoogui', with ngMaterial a dependencies
    // ngMaterial supplies material design
    angular.module('choochoogui', ['ngMaterial'])

        // configures the color theme in cyan and orange
        .config(function($mdThemingProvider) {
            $mdThemingProvider.theme('default')
                .primaryPalette('cyan')
                .accentPalette('orange');
        })

        .service('toastService', function($mdToast) {
            this.showToast = function(content) {
                var toast = $mdToast.simple()
                    .content(content)
                    .action('OK')
                    .position('bottom left');
                $mdToast.show(toast);
            }
        })

        .service('httpService', function(dataService, toastService, $http, $log) {

            this.sendTrainOperation = function(id, oper, value) {
                //does an HTTP POST of configured policy data
                $http.post(trainOperationUrl,
                    {
                        "input": {
                            "loco-id": id,
                            "control-parm": [
                                {
                                    "name": oper,
                                    "content-json-string": "{\""+oper+"\":\""+value+"\"}"
                                }
                            ]
                        }
                    },
                    {
                        headers: {
                            'Content-Type': 'application/json',
                            "Authorization": "Basic " + btoa(username + ":" + password)
                        }
                    }).then(function successCallback(response) {
                    $log.info(response);
                    if (response.data.output.status === 'OK') {
                        toastService.showToast('Operation sent!'); //show toast notification is successful
                    } else {
                        toastService.showToast('Error sending operation.'); //show error notification if unsuccessful
                    }
                }, function errorCallback(response) {
                    toastService.showToast('Error sending operation.'); //show error notification if unsuccessful
                    $log.error(response);
                });
            };

            this.getTrainTopoData = function(finishedLoadingCallback) {
                //does an HTTP GET for all DMO devices
                $http.get(trainTopoUrl, {
                    headers: {
                        'Content-Type': 'application/json',
                        "Authorization": "Basic " + btoa(username + ":" + password)
                    }
                }).then(function successCallback(response) {
                    // processes that data and stored in topologyData
                    dataService.setTopologyData(processTopologyData(response.data));
                    finishedLoadingCallback();
                }, function errorCallback(response) {
                    $log.error(response); // log an error it unsuccessful
                    finishedLoadingCallback();
                });
            };
        })
        .service('dataService', function() {
            nx.graphic.Icons.registerIcon("truck", "resources/moddedtruck.svg", 45, 45);
            this.topo = new nx.graphic.Topology({
                adaptive:true,
                scalable: true,
                theme:'blue', //...
                enableGradualScaling:true,
                nodeConfig: {
                    label: 'model.label',
                    scale: 'model.scale',
                    color: '#00bcd4',
                    iconType:function(vertex) {
                        if (vertex.get("name") === anchorName) {
                            return 'host'
                        } else {
                            return 'truck'
                        }
                    }
                },
                linkConfig: {
                    color: '#00bcd4',
                    linkType: 'parallel'
                },
                showIcon: true,
                dataProcessor:'force',
                autoLayout: true,
                enableSmartNode: false
            });
            this.nxApp = new nx.ui.Application;
            this.nxApp.container(document.getElementById('next-app'));
            this.topologyData = [];
            this.trains = [];
            this.setTopologyData = function(topoData) {
                this.topologyData = topoData;
                angular.copy(_.slice(this.topologyData.nodes,1), this.trains); //removes anchor
                this.topo.data(this.topologyData); //sets data to the NeXt topology
                this.topo.attach(this.nxApp); // attaches the NeXt topology
            };
        })

        //Main App Controller
        .controller('AppController',['$timeout', '$mdSidenav', '$log', '$mdDialog', '$mdToast', 'dataService',
            function ($timeout, $mdSidenav, $log, $mdDialog, $mdToast, dataService) {
                var vm = this;

                dataService.topo.on('topologyGenerated', function() {
                    dataService.topo.tooltipManager().showNodeTooltip(false);
                    dataService.topo.tooltipManager().showLinkTooltip(false);
                    dataService.topo.on('clickNode',function(topo,node) {
                        console.log(node.model().get());
                    });
                    window.addEventListener('resize', function(){
                        dataService.topo.adaptToContainer();
                    });
                });

                //opens right panel
                vm.openRight = function() {
                    $mdSidenav('right').open();
                };

                //toggles left panel
                vm.toggleLeft = function() {
                    $mdSidenav('left').toggle();
                };
        }])

        //directive handles the dmo cards (left hand side)
        .directive('trainCard', function() {
            return {
                restrict: 'E',
                templateUrl: '../templates/train-card-template.html',
                controller: function(httpService, dataService) {
                    var vm = this;
                    vm.waiting = true;
                    vm.trains = dataService.trains;

                    vm.finishedLoadingTrains = function(data) {
                        vm.waiting = false;
                        vm.targetData = data;
                    };
                    httpService.getTrainTopoData(vm.finishedLoadingTrains);
                },
                controllerAs: 'TrainCtrl'
            }
        })

        .directive('operationCard', function() {
            return {
                restrict: 'E',
                templateUrl: '../templates/train-operation-card-template.html'
            }
        })

        .controller('TrainOperationController',['httpService', function(httpService) {
            var vm = this;
            vm.waiting = false;

            // function called when the user clicks 'view sensors'
            vm.loadOperations = function(train) {
                vm.operations = ['headlight', 'bell'];
                vm.trainId = train['default-loco-id'];
            };

            vm.doOper = function(id, oper, value){
                httpService.sendTrainOperation(id, oper, value)
            };
        }]);
})(angular);