var processTopologyData = function(receivedData) {
    function Link (source, target) { //Link Class
        this.source = source;
        this.target = target;
    }

    console.log('Received:',receivedData);

    var topoData = {
        nodes:[receivedData['train-topology']],
        links: []
    };

    _.forEach(topoData.nodes, function(train, index) {
            train.scale = 1;
            train.label = train.name;
            train.locoId = train['default-loco-id'];
            train.label = 'Train (ID: '+train.locoId+')';
            topoData.links.push(new Link(0, index+1)); //Adds links from anchor to each node
    });

    //inserts node at beginning
    topoData.nodes.unshift({
        name: anchorName,
        label: anchorName,
        scale: 2,
        x: 75,
        y: 0
    });

    console.log('Processed:',topoData);

    return topoData;
};