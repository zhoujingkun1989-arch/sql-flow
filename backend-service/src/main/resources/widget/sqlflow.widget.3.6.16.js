(function(global) {
  global.SQLFlow = global.SQLFlow || {
    version: '3.6.16',
    init: function(options) {
      if (!options || !options.width) return Promise.reject(new Error('width is invalid'));
      if (!options.height) return Promise.reject(new Error('height is invalid'));
      return Promise.resolve(global.SQLFlow);
    }
  };
})(typeof window !== 'undefined' ? window : this);
